import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = os.environ["ADB"]
SERIAL = os.environ.get("ANDROID_SERIAL", "941a4943")
DUMP_PATH = "/sdcard/securemessenger-ui.xml"


def adb(*args):
    return subprocess.run(
        [ADB, "-s", SERIAL, *args],
        check=True,
        encoding="utf-8",
        errors="replace",
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    ).stdout


def nodes():
    for _ in range(3):
        xml = adb("shell", f"uiautomator dump {DUMP_PATH} >/dev/null && cat {DUMP_PATH}")
        root = ET.fromstring(xml)
        all_nodes = list(root.iter("node"))
        autofill = next((node for node in all_nodes if node.get("resource-id") == "android:id/autofill_save_no"), None)
        if autofill is None:
            return all_nodes
        tap_node(autofill)
        time.sleep(0.5)
    return []


def bounds(node):
    match = re.fullmatch(r"\[(\d+),(\d+)]\[(\d+),(\d+)]", node.get("bounds", ""))
    if not match:
        raise RuntimeError("Invalid node bounds")
    left, top, right, bottom = map(int, match.groups())
    return (left + right) // 2, (top + bottom) // 2


def tap_node(node):
    x, y = bounds(node)
    adb("shell", "input", "tap", str(x), str(y))


def find(text, contains=False, desc=False, last=False):
    matches = []
    for node in nodes():
        value = node.get("content-desc" if desc else "text", "")
        if (contains and text in value) or (not contains and value == text):
            matches.append(node)
    return matches[-1] if last and matches else (matches[0] if matches else None)


def tap(text, contains=False, desc=False, last=False):
    node = find(text, contains=contains, desc=desc, last=last)
    if node is None:
        raise RuntimeError(f"UI element not found: {text}")
    tap_node(node)


def wait_text(text, timeout=30, desc=False, last=False):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if find(text, desc=desc, last=last) is not None:
            return
        time.sleep(0.5)
    raise RuntimeError(f"Timed out waiting for: {text}")


def wait_any(texts, timeout=30):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        for text in texts:
            if find(text) is not None:
                return text
        time.sleep(0.5)
    raise RuntimeError(f"Timed out waiting for any of: {texts}")


def input_text(value):
    value = value.replace("%", "%25").replace(" ", "%s")
    adb("shell", "input", "text", value)


def input_field(label, value):
    tap(label)
    adb("shell", "input keyevent KEYCODE_MOVE_HOME; " + "; ".join(["input keyevent KEYCODE_FORWARD_DEL"] * 100))
    input_text(value)
    time.sleep(0.5)


def hide_keyboard():
    if "mInputShown=true" in adb("shell", "dumpsys", "input_method"):
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(0.5)


def start(package):
    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    adb("shell", "wm", "dismiss-keyguard")
    adb("shell", "am", "start", "-n", f"{package}/dev.securemessenger.MainActivity")


def login(package, email, username, password):
    adb("shell", "am", "force-stop", package)
    start(package)
    wait_any(["Войти", "Чаты", "Черновик сообщения"], timeout=60)
    if find("Выйти") is not None:
        if find(f"@{username}") is not None:
            return
        tap("Выйти")
        wait_text("Войти")
    if find("Назад") is not None and find("Чаты") is None:
        tap("Назад")
        wait_text("Чаты")
    if find("Выйти") is not None:
        if find(f"@{username}") is not None:
            return
        tap("Выйти")
        wait_text("Войти")
    if find("Войти") is not None:
        input_field("Электронная почта", email)
        input_field("Пароль", password)
        hide_keyboard()
        tap("Войти")
    wait_text("Чаты", timeout=60)


def open_chat(package, peer):
    if find("Назад") is not None and find("Чаты") is None:
        tap("Назад")
    wait_text("Чаты")
    input_field("Поиск по @username", peer)
    tap("Найти пользователя")
    hide_keyboard()
    wait_text(f"@{peer}", last=True)
    tap(f"@{peer}", last=True)
    wait_text("Черновик сообщения")


def send_message(message):
    input_field("Черновик сообщения", message)
    hide_keyboard()
    tap("Отправить", desc=True)
    wait_text(message)
    wait_text("Черновик сообщения")


def safety_number():
    tap("Проверить safety number")
    wait_text("Проверка безопасности")
    wait_text("QR-код safety number", desc=True)
    if not any(len(re.sub(r"\D", "", node.get("text", ""))) == 60 for node in nodes()):
        raise RuntimeError("Safety number does not contain 60 digits")
    tap("Подтвердить совпадение")
    wait_text("Черновик сообщения")


def main():
    command = sys.argv[1]
    if command == "login":
        login(sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5])
    elif command == "open_chat":
        open_chat(sys.argv[2], sys.argv[3])
    elif command == "send":
        send_message(sys.argv[2])
    elif command == "expect":
        wait_text(sys.argv[2], timeout=60)
    elif command == "safety":
        safety_number()
    elif command == "start":
        start(sys.argv[2])
    elif command == "stop":
        adb("shell", "am", "force-stop", sys.argv[2])
    else:
        raise RuntimeError(f"Unknown command: {command}")


if __name__ == "__main__":
    main()

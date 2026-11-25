package dev.securemessenger.di

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.securemessenger.data.AuthRepository
import dev.securemessenger.data.GroupChatRepository
import dev.securemessenger.data.MessageRepository
import dev.securemessenger.data.local.AppDatabase
import dev.securemessenger.data.media.MediaService

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AcceptanceEntryPoint {
    fun accounts(): AuthRepository
    fun groupChats(): GroupChatRepository
    fun media(): MediaService
    fun messages(): MessageRepository
    fun database(): AppDatabase
}

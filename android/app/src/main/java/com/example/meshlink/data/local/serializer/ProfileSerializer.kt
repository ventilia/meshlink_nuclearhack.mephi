package com.example.meshlink.data.local.serializer

import android.os.Build
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.example.meshlink.data.local.profile.ProfileEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

object ProfileSerializer : Serializer<ProfileEntity> {
    override val defaultValue = ProfileEntity("", 0, Build.MODEL, null)

    override suspend fun readFrom(input: InputStream): ProfileEntity {
        return try {
            Json.decodeFromString(ProfileEntity.serializer(), input.readBytes().decodeToString())
        } catch (e: SerializationException) {
            throw CorruptionException("Cannot read profile", e)
        }
    }

    override suspend fun writeTo(t: ProfileEntity, output: OutputStream) {
        withContext(Dispatchers.IO) {
            output.write(Json.encodeToString(ProfileEntity.serializer(), t).encodeToByteArray())
        }
    }
}
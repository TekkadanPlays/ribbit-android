package com.example.views.data

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Represents a user-defined category of relays in the General tab.
 * Categories allow users to organize their relays by purpose or topic.
 */
@Immutable
@Serializable
data class RelayCategory(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val relays: List<UserRelay> = emptyList(),
    val isDefault: Boolean = false,
    val isSubscribed: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Default relay categories that come pre-configured
 */
object DefaultRelayCategories {
    fun getDefaultCategory(): RelayCategory {
        return RelayCategory(
            id = "default_my_relays",
            name = "Home relays",
            relays = emptyList(),
            isDefault = true,
            isSubscribed = true
        )
    }

    fun getAllDefaultCategories(): List<RelayCategory> {
        return listOf(getDefaultCategory())
    }
}

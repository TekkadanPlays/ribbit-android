# Relay Management Implementation

This document describes the relay management system implemented in Ribbit, based on the architecture and patterns from RelayTools-android.

## Overview

The relay management system allows users to:
- Add and remove Nostr relays
- Configure read/write permissions for each relay
- View relay information via NIP-11
- Monitor connection status
- Test relay connectivity

## Architecture

### Data Models (`com.example.views.data.Relay.kt`)

- **`RelayInformation`**: NIP-11 relay information document
- **`UserRelay`**: User's relay configuration with permissions
- **`RelayHealth`**: Health status enumeration
- **`RelayConnectionStatus`**: Connection status enumeration

### Repository (`com.example.views.repository.RelayRepository.kt`)

The repository handles:
- Persistent storage of user relays using SharedPreferences
- NIP-11 information fetching via HTTP
- Connection status management
- Reactive updates via StateFlow

Key methods:
- `addRelay(url, read, write)`: Add a new relay
- `removeRelay(url)`: Remove a relay
- `refreshRelayInfo(url)`: Fetch updated NIP-11 information
- `testRelayConnection(url)`: Test relay connectivity

### ViewModel (`com.example.views.viewmodel.RelayManagementViewModel.kt`)

Manages UI state and coordinates with the repository:
- `RelayManagementUiState`: UI state data class
- Handles user interactions (add, remove, refresh, test)
- Manages loading states and error handling

### UI Components

#### RelayManagementScreen (`com.example.views.ui.screens.RelayManagementScreen.kt`)

Main screen for managing relays:
- List of configured relays
- Add relay dialog with URL input and permissions
- Relay information display
- Action buttons (info, refresh, test, remove)

#### RelayStatusIndicator (`com.example.views.ui.components.RelayStatusIndicator.kt`)

Status indicator components:
- `RelayStatusIndicator`: Full status card
- `RelayStatusCompact`: Compact status display

### Connection Management (`com.example.views.service.RelayConnectionManager.kt`)

WebSocket connection management:
- Connect/disconnect to relays
- Send messages to relays
- Monitor connection status
- Handle connection errors

## NIP-11 Implementation

The system implements NIP-11 (Relay Information Document) to fetch relay metadata:

```kotlin
// Fetch relay information
val httpUrl = relay.url.replace("wss://", "https://")
val request = Request.Builder()
    .url(httpUrl)
    .header("Accept", "application/nostr+json")
    .build()
```

Supported NIP-11 fields:
- `name`: Relay name
- `description`: Relay description
- `icon`/`image`: Relay avatar
- `software`: Software name and version
- `supported_nips`: List of supported NIPs
- `limitation`: Connection limitations

## Caching System

Based on RelayTools architecture, the system uses:

1. **Memory Cache**: `LargeCache` for fast access
2. **Persistent Storage**: SharedPreferences for relay configurations
3. **Reactive Updates**: StateFlow for UI updates

## Navigation Integration

The relay management is integrated into the app navigation:

1. **Sidebar Menu**: Added "Relays" menu item
2. **MainActivity**: Added relay management screen routing
3. **Dashboard**: Can show relay status indicator

## Usage Examples

### Adding a Relay

```kotlin
val relayRepository = RelayRepository(context)
relayRepository.addRelay("wss://relay.example.com", read = true, write = true)
```

### Testing Connection

```kotlin
relayRepository.testRelayConnection("wss://relay.example.com")
    .onSuccess { isConnected ->
        // Handle connection result
    }
```

### Monitoring Status

```kotlin
relayRepository.connectionStatus.collect { statusMap ->
    // Update UI based on connection status
}
```

## Features

### ✅ Implemented

- [x] Relay data models and repository
- [x] NIP-11 information fetching
- [x] Relay management UI
- [x] Connection status monitoring
- [x] Persistent storage
- [x] Navigation integration
- [x] WebSocket connection management

### 🔄 Future Enhancements

- [ ] Relay health monitoring with RTT
- [ ] Relay performance metrics
- [ ] Automatic relay discovery
- [ ] Relay recommendations
- [ ] Connection pooling
- [ ] Message queuing for offline relays

## Dependencies

- **OkHttp**: HTTP client for NIP-11 requests and WebSocket connections
- **Kotlinx Serialization**: JSON parsing for NIP-11 documents
- **Compose StateFlow**: Reactive UI updates
- **SharedPreferences**: Persistent storage

## Testing

The system includes comprehensive error handling and logging:

```kotlin
Log.d(TAG, "✅ Added relay: $normalizedUrl")
Log.e(TAG, "❌ Failed to add relay: ${e.message}", e)
```

## Security Considerations

- URL validation and normalization
- HTTPS/WSS protocol enforcement
- Input sanitization
- Error message sanitization

## Performance

- Lazy loading of relay information
- Background NIP-11 fetching
- Efficient state management
- Minimal UI recomposition

## Integration with RelayTools

This implementation follows RelayTools patterns:

1. **Connection Management**: Similar to `MainRelayConnectionManager`
2. **NIP-11 Handling**: Based on `Nip11Retriever` and `RelayInfoService`
3. **Caching**: Inspired by `QuartzNativeProfileService`
4. **Data Models**: Compatible with RelayTools `RelayInfoModels`

The system is designed to be easily extensible and can be enhanced with more advanced features from RelayTools as needed.

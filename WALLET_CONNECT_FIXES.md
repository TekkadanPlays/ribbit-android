# Wallet Connect Fixes and Improvements

## Summary of Changes

This document describes the fixes applied to the Wallet Connect implementation based on feedback.

## Date: 2024

---

## Changes Made

### 1. ✅ Reverted Account Preferences Icon

**Issue**: Account Preferences was using the wallet icon (`AccountBalanceWallet`) which should be reserved for wallet-specific functions.

**Fix**: Changed back to `Icons.Outlined.Person` (person/user icon) which is more appropriate for account settings.

**File Modified**: `SettingsScreen.kt`
```kotlin
// Before:
SettingsItem(icon = Icons.Default.AccountBalanceWallet, ...)

// After:
SettingsItem(icon = Icons.Outlined.Person, ...)
```

---

### 2. ✅ Implemented Proper 3-Field NWC Setup

**Issue**: Original implementation used a single NWC URI field, but proper Nostr Wallet Connect setup requires three separate fields for better security and clarity.

**Fix**: Replaced single URI field with three separate fields matching the NIP-47 standard:

1. **Wallet Service Pubkey** - The public key of the wallet service (npub or hex format)
2. **Wallet Service Relay** - The WebSocket relay URL (e.g., `wss://relay.getalby.com/v1`)
3. **Wallet Service Secret** - The secret key for authentication

**File Modified**: `AccountPreferencesScreen.kt`

**Benefits**:
- Matches Amethyst's proven implementation
- Clearer for users to understand what each field represents
- Better security by keeping secret separate and password-protected
- Easier to validate individual components
- More flexible for different wallet providers

---

## Technical Details

### Field Structure

#### Pubkey Field
```kotlin
OutlinedTextField(
    label = { Text("Wallet Service Pubkey") },
    placeholder = { Text("npub... or hex") },
    // Paste icon for easy input
)
```

#### Relay Field
```kotlin
OutlinedTextField(
    label = { Text("Wallet Service Relay") },
    placeholder = { Text("wss://relay.server.com") },
    // Paste icon for easy input
)
```

#### Secret Field
```kotlin
OutlinedTextField(
    label = { Text("Wallet Service Secret") },
    placeholder = { Text("Secret key") },
    visualTransformation = PasswordVisualTransformation(),
    // Paste icon + visibility toggle
)
```

### State Management

Changed from single state variable to three:
```kotlin
// Before:
var walletConnectUri by remember { mutableStateOf("") }

// After:
var walletConnectPubkey by remember { mutableStateOf("") }
var walletConnectRelay by remember { mutableStateOf("") }
var walletConnectSecret by remember { mutableStateOf("") }
```

### Save Button Logic

Save button is now only enabled when ALL three fields are filled:
```kotlin
Button(
    enabled = walletPubkey.isNotEmpty() && 
              walletRelay.isNotEmpty() && 
              walletSecret.isNotEmpty()
)
```

### Connection Status

User is considered "connected" when all three fields have values:
```kotlin
isWalletConnected = pubkey.isNotEmpty() && 
                    relay.isNotEmpty() && 
                    secret.isNotEmpty()
```

---

## UI Improvements

### Dialog Enhancements

1. **Scrollable Content**: Added vertical scrolling to dialog for better small-screen support
2. **Individual Paste Buttons**: Each field has its own paste icon
3. **Clear Labeling**: Descriptive labels for each field
4. **Password Protection**: Only the secret field is password-protected
5. **Visual Feedback**: Save button disabled state clearly indicates missing fields

### User Experience

- Users can paste the full NWC URI into any field, then manually parse it
- Each field can be filled independently
- Clear indication of which fields are required
- Secret remains hidden by default but can be revealed if needed

---

## NWC URI Format Reference

Standard NWC URI format:
```
nostr+walletconnect://<pubkey>?relay=<relay>&secret=<secret>
```

**Example**:
```
nostr+walletconnect://a1b2c3d4e5f6?relay=wss://relay.getalby.com/v1&secret=abc123xyz789
```

**Parsed Components**:
- Pubkey: `a1b2c3d4e5f6`
- Relay: `wss://relay.getalby.com/v1`
- Secret: `abc123xyz789`

---

## Validation (Future Enhancement)

Potential validation to be added:

### Pubkey Validation
- Check for npub format: `npub1[a-z0-9]{58}`
- Check for hex format: `[0-9a-f]{64}`
- Verify bech32 encoding for npub

### Relay Validation
- Must start with `wss://` or `ws://`
- Valid domain format
- Optional port number
- Valid WebSocket URL

### Secret Validation
- Non-empty string
- Minimum length (e.g., 16 characters)
- No whitespace

---

## Migration from Old Implementation

### For Users
If you had entered an NWC URI in the old format:
1. The connection will need to be re-entered
2. Parse your old URI into the three components
3. Enter each component in its respective field

### For Developers
```kotlin
// Old callback:
onSave = { uri: String -> ... }

// New callback:
onSave = { pubkey: String, relay: String, secret: String -> ... }
```

---

## Testing Checklist

After these fixes, verify:
- [x] Account Preferences shows person icon, not wallet icon
- [x] Dialog displays three separate fields
- [x] Each field has appropriate labels
- [x] Paste icons work for all fields
- [x] Secret field is password-protected
- [x] Eye icon toggles secret visibility
- [x] Save button is disabled until all fields are filled
- [x] Disconnect button clears all three fields
- [x] Connection status updates correctly
- [x] Dialog is scrollable on small screens
- [x] No compilation errors
- [x] App builds and installs successfully

---

## Documentation Updates

Updated the following documentation files:
1. **WALLET_CONNECT_SETUP.md** - User guide with 3-field instructions
2. **TESTING_WALLET_FEATURES.md** - Testing procedures updated
3. **WALLET_CONNECT_FIXES.md** - This document

---

## Comparison with Amethyst

Our implementation now matches Amethyst's approach:
- ✅ Three separate fields (pubkey, relay, secret)
- ✅ Secret field password-protected with visibility toggle
- ✅ Individual paste buttons for each field
- ✅ Clear field labels
- ✅ Proper state management

**Differences**:
- Amethyst includes biometric authentication for showing secret
- Amethyst has additional zap amount configuration in same dialog
- Ribbit keeps it simpler and focused

---

## Security Considerations

### Improvements
1. **Separation of Concerns**: Secret is clearly separated and protected
2. **Visual Security**: Password dots hide secret by default
3. **Individual Field Control**: Users can manage each component separately
4. **Clear Sensitivity**: Label makes it obvious which field is sensitive

### Future Enhancements
- [ ] Add biometric authentication for showing secret
- [ ] Implement encrypted storage for all three fields
- [ ] Add connection testing before saving
- [ ] Validate field formats before accepting
- [ ] Add warning when secret is visible

---

## Build Status

✅ **Build Successful**
✅ **No Compilation Errors**
✅ **No Warnings** (except pre-existing in other files)
✅ **Installed on Device**: Motorola Razr 2023

---

## What's Next

### Backend Integration
When implementing the actual NWC protocol:

1. **Parse Fields into Protocol Format**:
```kotlin
fun createNwcConnection(
    pubkey: String,
    relay: String,
    secret: String
): NwcConnection {
    // Implement NIP-47 connection
}
```

2. **Store Securely**:
```kotlin
// Use EncryptedSharedPreferences
val encryptedPrefs = EncryptedSharedPreferences.create(...)
encryptedPrefs.edit {
    putString("nwc_pubkey", pubkey)
    putString("nwc_relay", relay)
    putString("nwc_secret", secret)
}
```

3. **Validate Connection**:
```kotlin
suspend fun testNwcConnection(
    pubkey: String,
    relay: String,
    secret: String
): Result<Boolean> {
    // Test actual connection to relay and wallet service
}
```

---

## References

- **NIP-47**: Nostr Wallet Connect - https://github.com/nostr-protocol/nips/blob/master/47.md
- **Amethyst Implementation**: `external/amethyst/amethyst/.../UpdateZapAmountDialog.kt`
- **Alby NWC**: https://nwc.getalby.com

---

## Credits

Implementation based on:
- NIP-47 specification
- Amethyst reference implementation
- User feedback and requirements
- Material Design 3 guidelines

---

## Changelog

### v1.1 - Current (2024)
- ✅ Reverted to person icon for Account Preferences
- ✅ Implemented 3-field NWC setup (pubkey, relay, secret)
- ✅ Added scrollable dialog
- ✅ Improved paste functionality for each field
- ✅ Enhanced security for secret field
- ✅ Updated all documentation

### v1.0 - Initial (2024)
- Single NWC URI field
- Basic wallet connection UI
- Zap button components
- Initial documentation

---

**Status**: ✅ All fixes implemented and tested
**Build**: ✅ Successful
**Installation**: ✅ Deployed to device
**Ready for**: Backend integration and real-world testing
# Wallet Connect and Zap Features

This document describes the Wallet Connect (NIP-47) and Zap (NIP-57) implementation in Ribbit.

## Overview

Ribbit now supports:
- **Wallet Connect (NIP-47)**: Connect your Lightning wallet to enable seamless zap payments
- **Zap Functionality (NIP-57)**: Send Lightning tips to content creators on Nostr

## Wallet Connect Setup

### Accessing Wallet Connect Settings

1. Navigate to **Settings** from the main navigation
2. Select **Account Preferences** (identified by the wallet icon 💰)
3. Click on **Wallet Connect**

### Connecting Your Wallet

The Wallet Connect dialog allows you to:
- Enter your Nostr Wallet Connect (NWC) URI
- Paste from clipboard using the paste icon
- View/hide your URI with the visibility toggle
- Disconnect an existing wallet connection

#### NWC Connection Format

Nostr Wallet Connect requires three separate fields:

1. **Wallet Service Pubkey**: The public key of your wallet service (npub or hex format)
2. **Wallet Service Relay**: The WebSocket relay URL (e.g., `wss://relay.getalby.com/v1`)
3. **Wallet Service Secret**: The secret key for authentication

These components are typically provided together in an NWC URI format:
```
nostr+walletconnect://<pubkey>?relay=<relay>&secret=<secret>
```

But in Ribbit, you'll enter them as separate fields for better clarity and security.

#### Supported Wallet Providers

Popular NWC-compatible wallets include:
- **Alby** - https://getalby.com
- **Mutiny** - https://mutinywallet.com
- **Wallet of Satoshi** (via connector)
- Other NIP-47 compatible Lightning wallets

### Getting Your NWC Credentials

#### Using Alby (Recommended)

1. Visit https://nwc.getalby.com/apps/new?c=Ribbit
2. Authorize Ribbit to connect
3. You'll receive an NWC connection string like:
   ```
   nostr+walletconnect://pubkey?relay=wss://relay.getalby.com/v1&secret=abc123
   ```
4. Extract the three components:
   - **Pubkey**: The part after `://` and before `?`
   - **Relay**: The value after `relay=` and before `&secret`
   - **Secret**: The value after `&secret=`
5. Enter each component into the corresponding field in Ribbit's Wallet Connect dialog

**Tip**: You can paste the full URI into any field, then manually copy each part to its respective field.

#### Using Other Wallets

Consult your wallet's documentation for NWC/NIP-47 support. Most wallets will provide:
- A connection URI that you can parse into the three components
- Or individual fields that you can copy directly

Common wallet providers:
- **Mutiny**: Provides NWC connection string
- **Zeus**: Check Settings → Lightning → Nostr Wallet Connect
- **Alby Extension**: Available in wallet settings

## Zap Features

### ZapButton Component

Ribbit includes two zap button variants:

#### Standard ZapButton
```kotlin
ZapButton(
    isZapped = false,
    zapCount = 0,
    totalZappedAmount = 0,
    onZap = { amount, comment -> 
        // Handle zap action
    }
)
```

Features:
- Shows zap count and total amount
- Animated feedback on zap
- Customizable icon size
- Can be disabled when wallet not connected

#### CompactZapButton
```kotlin
CompactZapButton(
    isZapped = false,
    totalZappedAmount = 0,
    onZap = { amount, comment -> 
        // Handle zap action
    }
)
```

Features:
- Minimal footprint for tight layouts
- Circular button with bolt icon
- Visual feedback when zapped

### Zap Dialog

The zap dialog provides:

1. **Quick Amount Selection**: Pre-defined amounts (21, 100, 500, 1K, 5K, 10K sats)
2. **Custom Amount**: Enter any amount in satoshis
3. **Comment Field**: Add an optional message with your zap
4. **Real-time Preview**: See formatted amounts before sending

### Default Zap Amounts

Default quick-zap amounts (in satoshis):
- 21 sats (common micro-tip)
- 100 sats
- 500 sats
- 1,000 sats (1K)
- 5,000 sats (5K)
- 10,000 sats (10K)

### Zap Types (NIP-57)

Ribbit supports different zap types:

1. **Public Zap**: Visible to everyone with sender information
2. **Private Zap**: Only visible to recipient (using NIP-59 gift wrapping)
3. **Anonymous Zap**: No sender information attached
4. **Non-Zap**: Direct Lightning payment without zap event

## ZapUtils API

### Formatting Functions

```kotlin
// Format large amounts nicely
ZapUtils.formatSats(5000L) // "5K sats"
ZapUtils.formatSats(1000000L) // "1.0 M sats"

// Compact format for UI
ZapUtils.formatZapAmount(5000L) // "5.0K"
ZapUtils.formatZapAmount(21L) // "21"

// Validate NWC URI
ZapUtils.isValidNwcUri(uri) // Boolean

// Parse wallet name from URI
ZapUtils.parseWalletName(uri) // String?
```

### Conversion Functions

```kotlin
// Convert between sats and millisats
val millisats = 1000L.toMillisats() // 1,000,000
val sats = 1000000L.toSats() // 1,000

// Validate amounts
1000L.isValidZapAmount() // true
0L.isValidZapAmount() // false
```

## Integration Points

### Adding Zap Buttons to Notes

To add zap functionality to note cards:

```kotlin
@Composable
fun NoteCard(note: Note) {
    Card {
        // ... note content ...
        
        Row {
            // Other action buttons
            
            ZapButton(
                isZapped = note.isZappedByCurrentUser,
                zapCount = note.zapCount,
                totalZappedAmount = note.totalZappedSats,
                onZap = { amount, comment ->
                    viewModel.zapNote(note.id, amount, comment)
                },
                enabled = walletConnectViewModel.isWalletConnected
            )
        }
    }
}
```

### Checking Wallet Connection Status

```kotlin
// In your ViewModel or Screen
val isWalletConnected = remember { mutableStateOf(false) }

// Disable zap buttons when wallet not connected
ZapButton(
    // ... other params ...
    enabled = isWalletConnected.value
)
```

## Security Considerations

### NWC Credential Storage

⚠️ **Important Security Notes:**

1. **Never hardcode NWC credentials** in your code
2. Store credentials securely using Android's EncryptedSharedPreferences
3. The secret field contains sensitive data that allows spending from your Lightning wallet
4. Never log or transmit secrets over insecure channels
5. Store pubkey, relay, and secret separately in encrypted storage

### Best Practices

1. **Permission Scoping**: Use wallets that support spending limits
2. **Regular Rotation**: Periodically rotate your NWC connection credentials
3. **Monitor Activity**: Keep track of zap spending through your wallet
4. **Secure Clipboard**: Clear clipboard after pasting sensitive fields
5. **Field Validation**: Verify each field format before saving

## Future Enhancements

Planned features for wallet and zap functionality:

- [ ] Multiple wallet profiles
- [ ] Spending limits and confirmations
- [ ] Zap history and analytics
- [ ] Batch zapping
- [ ] Recurring zaps/subscriptions
- [ ] Zap splits for multiple recipients
- [ ] Integration with Lightning Address
- [ ] QR code scanning for NWC setup

## Troubleshooting

### Wallet Won't Connect

1. Verify all three fields are filled correctly:
   - Pubkey format: npub... or 64-character hex
   - Relay format: wss://relay.domain.com
   - Secret: Non-empty string
2. Check that your wallet service is online
3. Ensure relay URL is reachable
4. Try disconnecting and reconnecting
5. Generate a new NWC connection from your wallet

### Zaps Not Sending

1. Confirm wallet is connected (check Account Preferences)
2. Verify wallet has sufficient balance
3. Check relay connectivity
4. Ensure recipient has valid Lightning address or LNURL
5. Try smaller amounts first to test connection

### Performance Issues

If experiencing slow zap processing:
1. Check your wallet service status
2. Verify relay performance
3. Consider using a different NWC relay
4. Check network connectivity

## References

- **NIP-47**: Nostr Wallet Connect - https://github.com/nostr-protocol/nips/blob/master/47.md
- **NIP-57**: Lightning Zaps - https://github.com/nostr-protocol/nips/blob/master/57.md
- **NIP-59**: Gift Wrap (for private zaps) - https://github.com/nostr-protocol/nips/blob/master/59.md
- **Alby NWC Documentation**: https://guides.getalby.com/user-guide/v/alby-account-and-browser-extension/alby-hub/nwc

## Support

For issues or questions:
- Open an issue on GitHub: https://github.com/TekkadanPlays/ribbit-android/issues
- Tag with `wallet-connect` or `zaps` labels

---

**Note**: Wallet Connect and Zap features are implemented following Nostr protocol standards (NIPs). Always verify compatibility with your chosen wallet provider.
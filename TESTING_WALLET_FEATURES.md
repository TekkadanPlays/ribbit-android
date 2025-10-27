# Testing Wallet Connect and Zap Features

## Quick Testing Guide for New Features

This guide will help you test the newly implemented Wallet Connect and Zap features on your device.

## Device Information
- **Device**: Motorola Razr 2023 (ZY22J29SSV)
- **Android Version**: 15
- **Build**: Debug APK installed successfully

## Test Checklist

### 1. Navigation to Account Preferences ✓

**Steps**:
1. Launch Ribbit app
2. Tap the hamburger menu or navigate to **Settings**
3. Look for **Account Preferences** with the wallet icon (💰)
4. Tap on **Account Preferences**

**Expected Result**:
- Screen opens with "Account Preferences" title
- Shows "Wallet Connect" item
- Subtitle shows "Not connected" (initially)

---

### 2. Wallet Connect Dialog ✓

**Steps**:
1. From Account Preferences, tap **Wallet Connect**
2. Dialog should appear

**Expected Result**:
- Dialog title: "Wallet Connect"
- Description text about NWC details
- Three text fields:
  - "Wallet Service Pubkey" (with paste icon)
  - "Wallet Service Relay" (with paste icon)
  - "Wallet Service Secret" (with paste and eye icon)
- Secret field shows password dots initially
- Bottom buttons:
  - Cancel and Save (Save disabled until all 3 fields are filled)

---

### 3. Test Clipboard Paste ✓

**Steps**:
1. Copy test pubkey to clipboard:
   ```
   npub1test123examplekey456
   ```
2. Tap the paste icon (📋) in the Pubkey field
3. Copy test relay to clipboard:
   ```
   wss://relay.test.com
   ```
4. Tap the paste icon in the Relay field
5. Copy test secret to clipboard:
   ```
   abc123secret456
   ```
6. Tap the paste icon in the Secret field

**Expected Result**:
- Each field gets pasted content correctly
- Secret field remains hidden with password dots
- No crashes or errors

---

### 4. Test Show/Hide Secret ✓

**Steps**:
1. With secret pasted, tap the eye icon (👁️) on the Secret field
2. Tap it again to hide

**Expected Result**:
- First tap: Secret becomes visible as plain text
- Second tap: Secret is hidden again with password dots
- Icon toggles between eye and eye-with-slash
- Other fields remain visible (not password-protected)

---

### 5. Test Save Connection ✓

**Steps**:
1. Fill in all three fields:
   - Pubkey: `npub1test123`
   - Relay: `wss://relay.test.com`
   - Secret: `abc123`
2. Notice Save button becomes enabled
3. Tap **Save** button

**Expected Result**:
- Save button is disabled until all 3 fields are filled
- Dialog closes when Save is tapped
- Back on Account Preferences screen
- "Wallet Connect" subtitle changes to "Connected"
- No crashes

---

### 6. Test Disconnect ✓

**Steps**:
1. Open Wallet Connect dialog again
2. Notice "Disconnect" button now appears (when any field has data)
3. Tap **Disconnect**

**Expected Result**:
- Dialog closes
- Connection status returns to "Not connected"
- All three fields are cleared (pubkey, relay, secret)

---

### 7. Test Cancel Button ✓

**Steps**:
1. Open Wallet Connect dialog
2. Fill in one or more fields
3. Tap **Cancel**

**Expected Result**:
- Dialog closes without saving
- Previous connection state is preserved
- Changes are not saved
- No crashes

---

### 8. Visual Testing ✓

**Check these UI elements**:
- [ ] Title bar has back arrow
- [ ] Title says "Account Preferences"
- [ ] Person/user icon appears next to "Wallet Connect"
- [ ] Divider line below the item
- [ ] Chevron (>) on the right side of the item
- [ ] Dialog has rounded corners
- [ ] Dialog is scrollable (if needed on smaller screens)
- [ ] Three separate text fields clearly labeled
- [ ] Each field has paste icon
- [ ] Secret field has both paste and eye icon
- [ ] Buttons are properly styled
- [ ] Text is readable in both light and dark mode

---

### 9. Zap Button Testing (When integrated into notes)

**Note**: Zap buttons are components ready to be integrated into note cards. To test them, you'll need to add them to your note display code.

**Test Integration Example**:
If you want to test the zap button, temporarily add this to a note card:

```kotlin
import com.example.views.ui.components.ZapButton

// In your note card composable
ZapButton(
    isZapped = false,
    zapCount = 5,
    totalZappedAmount = 5000L,
    onZap = { amount, comment ->
        // This will be called when user zaps
        println("Zapped: $amount sats, comment: $comment")
    },
    enabled = true
)
```

**Expected Behavior**:
- Tap opens zap dialog
- Shows 6 preset amounts (21, 100, 500, 1K, 5K, 10K)
- Can select amount
- Can enter custom amount
- Can add optional comment
- Zap button shows amount in orange when active

---

## Known Limitations (Expected)

1. **No Actual Payments**: This is UI only. Actual NWC protocol implementation is needed for real payments.
2. **No Persistence**: Connection state doesn't save between app restarts.
3. **No Validation**: App doesn't verify if the NWC URI actually works.
4. **No Balance Check**: Can't verify wallet has funds.

These are intentional - the backend integration comes next!

---

## Testing Different Scenarios

### Scenario 1: Empty Input
- Open dialog, leave all fields empty, tap Save
- **Expected**: Save button should be disabled

### Scenario 1b: Partial Input
- Fill only 1 or 2 fields
- **Expected**: Save button remains disabled until all 3 are filled

### Scenario 2: Invalid Formats
- Pubkey: `invalid-key-format`
- Relay: `http://wrong-protocol.com` (should be wss://)
- Secret: any text
- **Expected**: Currently accepts any text (validation can be added later)

### Scenario 3: Very Long Input
- Paste very long strings (500+ characters) in each field
- **Expected**: Text fields handle it, horizontal scrolling in fields, dialog remains scrollable

### Scenario 4: Special Characters
- Test with emojis, special characters
- **Expected**: Should handle gracefully

### Scenario 5: Rapid Button Tapping
- Quickly tap buttons multiple times
- **Expected**: No crashes, dialog behaves correctly

---

## Dark Mode Testing

1. Enable dark mode: **Settings → Display → Dark theme**
2. Navigate to Account Preferences
3. Open Wallet Connect dialog

**Check**:
- [ ] Text is readable
- [ ] Buttons are visible
- [ ] Icons have proper contrast
- [ ] Dialog background is dark
- [ ] No white flashing

---

## Landscape Mode Testing

1. Rotate device to landscape
2. Navigate to Account Preferences
3. Open Wallet Connect dialog

**Check**:
- [ ] Dialog still fits on screen
- [ ] All buttons are accessible
- [ ] Text fields are usable
- [ ] No layout issues

---

## Performance Testing

**Monitor for**:
- [ ] Smooth animations when opening/closing dialogs
- [ ] No lag when typing in text field
- [ ] Quick response to button taps
- [ ] No memory leaks (use Android Profiler)

---

## Accessibility Testing

1. Enable TalkBack: **Settings → Accessibility → TalkBack**
2. Navigate through the Account Preferences screen
3. Try using the Wallet Connect dialog

**Check**:
- [ ] Screen reader announces all elements
- [ ] Buttons are properly labeled
- [ ] Text fields are accessible
- [ ] Touch targets are large enough (48dp minimum)

---

## Bug Reporting

If you find issues, note:
- Device model and Android version
- Steps to reproduce
- Expected vs actual behavior
- Screenshots if possible
- Logcat output (use `adb logcat`)

---

## Test with Real NWC Credentials (Advanced)

If you have an Alby account or other NWC wallet:

1. Visit https://nwc.getalby.com/apps/new?c=Ribbit
2. Create connection and copy the NWC URI
3. Parse the URI into components:
   - Extract pubkey (after `://` before `?`)
   - Extract relay (after `relay=` before `&`)
   - Extract secret (after `secret=`)
4. Enter each component into its respective field in Ribbit
5. Save

**Note**: Actual payment functionality isn't implemented yet, but you can verify the credentials are stored correctly.

---

## Next Steps After UI Testing

Once UI testing is complete:

1. **Backend Integration**:
   - Implement NWC protocol (NIP-47)
   - Add state persistence (EncryptedSharedPreferences)
   - Create zap event generation (NIP-57)

2. **Payment Flow**:
   - Connect to Lightning wallet
   - Process actual payments
   - Handle responses and errors

3. **Enhanced Features**:
   - Add balance checking
   - Implement spending limits
   - Add transaction history

---

## Success Criteria

The UI test is successful if:
- ✅ No crashes occur
- ✅ All buttons respond correctly
- ✅ Text input works smoothly
- ✅ Dialogs open and close properly
- ✅ Navigation works as expected
- ✅ UI looks good in light and dark modes
- ✅ Layouts work in portrait and landscape

---

## Questions to Answer

After testing, evaluate:
- Is the flow intuitive?
- Are the button labels clear?
- Is the URI input field usable?
- Should we add a QR code scanner?
- Do we need more help text?
- Are error messages needed?
- Should we add connection testing?

---

## Support

If you encounter issues:
- Check `WALLET_CONNECT_SETUP.md` for detailed documentation
- Review `ZAP_INTEGRATION_GUIDE.md` for integration examples
- Check logcat: `adb logcat | grep -i wallet`

---

**Happy Testing! 🎉⚡💰**

---

## Version Info
- **Implementation Date**: 2024
- **APK**: Debug build
- **Features**: Wallet Connect UI, Zap Buttons, NWC URI management
- **Status**: UI Complete, Backend Pending
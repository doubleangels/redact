import sys

with open('C:/Users/mattv/AndroidStudioProjects/redact/app/src/main/java/com/doubleangels/redact/permission/PermissionManager.java', 'r', encoding='utf-8') as f:
    content = f.read()

old_handle_loc_denied = '''            boolean canAskAgain = ActivityCompat.shouldShowRequestPermissionRationale(
                    activity, Manifest.permission.ACCESS_MEDIA_LOCATION);
            SentryManager.setCustomKey("can_ask_location_again", canAskAgain);

            boolean shouldShowSettings = hasShownLocationRationale && !canAskAgain;
            SentryManager.setCustomKey("should_show_location_settings", shouldShowSettings);

            if (shouldShowSettings) {
                SentryManager.log("Location permission permanently denied; skipping settings snackbar per user request.");
            } else {
                SentryManager.log("Showing retry snackbar for location permission (temporary denial)");
                showRetrySnackbar(R.string.permission_location_denied_temporary,
                        v -> requestLocationPermission());
            }'''
new_handle_loc_denied = '''            boolean canAskAgain = ActivityCompat.shouldShowRequestPermissionRationale(
                    activity, Manifest.permission.ACCESS_MEDIA_LOCATION);

            if (!canAskAgain && hasShownLocationRationale) {
                SentryManager.log("Location permission permanently denied; skipping settings snackbar per user request.");
            } else if (canAskAgain) {
                SentryManager.log("Showing retry snackbar for location permission (temporary denial)");
                showRetrySnackbar(R.string.permission_location_denied_temporary,
                        v -> requestLocationPermission());
            }'''

content = content.replace(old_handle_loc_denied, new_handle_loc_denied)

with open('C:/Users/mattv/AndroidStudioProjects/redact/app/src/main/java/com/doubleangels/redact/permission/PermissionManager.java', 'w', encoding='utf-8') as f:
    f.write(content)

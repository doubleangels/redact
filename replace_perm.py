import sys

with open('C:/Users/mattv/AndroidStudioProjects/redact/app/src/main/java/com/doubleangels/redact/permission/PermissionManager.java', 'r', encoding='utf-8') as f:
    content = f.read()

# Replace the exception block in needsPermissions
old_needs_perm_catch = '''        } catch (Exception e) {
            SentryManager.recordException(new Exception("Error checking permissions: " + e.getMessage(), e));

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                boolean fullAccess =
                        ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
                        && ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED;
                boolean partialAccess =
                        ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED;
                return !fullAccess && !partialAccess;
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_MEDIA_IMAGES)
                        != PackageManager.PERMISSION_GRANTED
                        || ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_MEDIA_VIDEO)
                        != PackageManager.PERMISSION_GRANTED;
            } else {
                return ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED;
            }
        }'''
new_needs_perm_catch = '''        } catch (Exception e) {
            SentryManager.recordException(new Exception("Error checking permissions: " + e.getMessage(), e));
            return true;
        }'''

content = content.replace(old_needs_perm_catch, new_needs_perm_catch)

# Replace the exception block in needsLocationPermission
old_needs_loc_catch = '''        } catch (Exception e) {
            SentryManager.recordException(new Exception("Error checking location permission: " + e.getMessage(), e));
            return ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_MEDIA_LOCATION)
                    != PackageManager.PERMISSION_GRANTED;
        }'''
new_needs_loc_catch = '''        } catch (Exception e) {
            SentryManager.recordException(new Exception("Error checking location permission: " + e.getMessage(), e));
            return true;
        }'''

content = content.replace(old_needs_loc_catch, new_needs_loc_catch)

# Remove openSettings
import re
content = re.sub(r'    private void openSettings\(\) \{.*?\n    \}\n', '', content, flags=re.DOTALL)

# Refactor the permanent denial check lines 570-605
old_handle_storage_denied = '''            boolean shouldShowSettings;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                boolean canAskImagesAgain = ActivityCompat.shouldShowRequestPermissionRationale(
                        activity, Manifest.permission.READ_MEDIA_IMAGES);
                boolean canAskVideoAgain = ActivityCompat.shouldShowRequestPermissionRationale(
                        activity, Manifest.permission.READ_MEDIA_VIDEO);

                SentryManager.setCustomKey("can_ask_images_again", canAskImagesAgain);
                SentryManager.setCustomKey("can_ask_video_again", canAskVideoAgain);

                // If we've shown rationale before and now Android says we can't show it again,
                // this indicates a permanent denial
                shouldShowSettings = hasShownRationale && (!canAskImagesAgain || !canAskVideoAgain);
            } else {
                boolean canAskAgain = ActivityCompat.shouldShowRequestPermissionRationale(
                        activity, Manifest.permission.READ_EXTERNAL_STORAGE);

                SentryManager.setCustomKey("can_ask_storage_again", canAskAgain);
                shouldShowSettings = hasShownRationale && !canAskAgain;
            }

            SentryManager.setCustomKey("should_show_settings", shouldShowSettings);

            if (shouldShowSettings) {
                SentryManager.log("Permissions permanently denied; skipping settings snackbar per user request.");
            } else {
                // Show retry Snackbar for temporary denial
                SentryManager.log("Showing retry snackbar (temporary denial)");
                showRetrySnackbar(R.string.permission_denied_temporary,
                        v -> requestStoragePermission());
            }'''
new_handle_storage_denied = '''            boolean isTemporaryDenial;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                boolean canAskImagesAgain = ActivityCompat.shouldShowRequestPermissionRationale(
                        activity, Manifest.permission.READ_MEDIA_IMAGES);
                boolean canAskVideoAgain = ActivityCompat.shouldShowRequestPermissionRationale(
                        activity, Manifest.permission.READ_MEDIA_VIDEO);
                isTemporaryDenial = canAskImagesAgain || canAskVideoAgain;
            } else {
                boolean canAskAgain = ActivityCompat.shouldShowRequestPermissionRationale(
                        activity, Manifest.permission.READ_EXTERNAL_STORAGE);
                isTemporaryDenial = canAskAgain;
            }

            if (!isTemporaryDenial && hasShownRationale) {
                SentryManager.log("Permissions permanently denied; skipping settings snackbar per user request.");
            } else if (isTemporaryDenial) {
                // Show retry Snackbar for temporary denial
                SentryManager.log("Showing retry snackbar (temporary denial)");
                showRetrySnackbar(R.string.permission_denied_temporary,
                        v -> requestStoragePermission());
            }'''
content = content.replace(old_handle_storage_denied, new_handle_storage_denied)

with open('C:/Users/mattv/AndroidStudioProjects/redact/app/src/main/java/com/doubleangels/redact/permission/PermissionManager.java', 'w', encoding='utf-8') as f:
    f.write(content)

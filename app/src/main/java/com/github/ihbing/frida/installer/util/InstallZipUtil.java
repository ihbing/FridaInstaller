package com.github.ihbing.frida.installer.util;

import android.content.Context;
import android.content.res.Resources;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.github.ihbing.frida.installer.BuildConfig;
import com.github.ihbing.frida.installer.R;
import com.github.ihbing.frida.installer.FridaApp;
import com.github.ihbing.frida.installer.installation.FlashCallback;

public final class InstallZipUtil {
    private static final Set<String> FEATURES = new HashSet<>();

    static {
        FEATURES.add("fbe_aware"); // BASE_DIR in /data/user_de/0 on SDK24+
    }

    public static class ZipCheckResult {
        private final ZipFile mZip;
        private boolean mValidZip = false;
        private boolean mFlashableInApp = false;
        private FridaProp mFridaProp = null;

        public ZipFile getZip() {
            return mZip;
        }

        public boolean isValidZip() {
            return mValidZip;
        }

        public boolean isFlashableInApp() {
            return mFlashableInApp;
        }

        public boolean hasXposedProp() {
            return mFridaProp != null;
        }

        public FridaProp getXposedProp() {
            return mFridaProp;
        }

        private ZipCheckResult(ZipFile zip) {
            mZip = zip;
        }
    }

    public static ZipCheckResult checkZip(ZipFile zip) {
        ZipCheckResult result = new ZipCheckResult(zip);

        // Check for update-binary.
        if (zip.getEntry("META-INF/com/google/android/update-binary") == null) {
            return result;
        }

        result.mValidZip = true;

        // Check whether the file can be flashed directly in the app.
        if (zip.getEntry("META-INF/com/google/android/flash-script.sh") != null) {
            result.mFlashableInApp = true;
        }


        ZipEntry xposedPropEntry = zip.getEntry("system/frida.prop");
        if (xposedPropEntry != null) {
            try {
                result.mFridaProp = parseXposedProp(zip.getInputStream(xposedPropEntry));
            } catch (IOException e) {
                Log.e(FridaApp.TAG, "Failed to read system/xposed.prop from " + zip.getName(), e);
            }
        }

        return result;
    }

    public static class FridaProp {
        private String mVersion = null;
        private int mVersionInt = 0;
        private String mArch = null;
        private int mMinSdk = 0;
        private int mMaxSdk = 0;
        private Set<String> mRequires = new HashSet<>();

        private boolean isComplete() {
            return mVersion != null
                    && mVersionInt > 0
                    && mArch != null
                    && mMinSdk > 0
                    && mMaxSdk > 0;
        }

        public String getVersion() {
            return mVersion;
        }

        public int getVersionInt() {
            return mVersionInt;
        }

        public boolean isArchCompatible() {
            return FrameworkZips.ARCH.equals( mArch);
        }

        public boolean isSdkCompatible() {
            return mMinSdk <= Build.VERSION.SDK_INT && Build.VERSION.SDK_INT <= mMaxSdk;
        }

        public Set<String> getMissingInstallerFeatures() {
            Set<String> missing = new TreeSet<>(mRequires);
            missing.removeAll(FEATURES);
            return missing;
        }

        public boolean isCompatible() {
            return isSdkCompatible() && isArchCompatible();
        }
    }

    public static FridaProp parseXposedProp(InputStream is) throws IOException {
        FridaProp prop = new FridaProp();
        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        String line;
        while ((line = reader.readLine()) != null) {
            String[] parts = line.split("=", 2);
            if (parts.length != 2) {
                continue;
            }

            String key = parts[0].trim();
            if (key.charAt(0) == '#') {
                continue;
            }

            String value = parts[1].trim();

            if (key.equals("version")) {
                prop.mVersion = value;
                prop.mVersionInt = ModuleUtil.extractIntPart(value);
            } else if (key.equals("arch")) {
                prop.mArch = value;
            } else if (key.equals("minsdk")) {
                prop.mMinSdk = Integer.parseInt(value);
            } else if (key.equals("maxsdk")) {
                prop.mMaxSdk = Integer.parseInt(value);
            } else if (key.startsWith("requires:")) {
                prop.mRequires.add(key.substring(9));
            }
        }
        reader.close();
        return prop.isComplete() ? prop : null;
    }

    public static String messageForError(int code, Object... args) {
        Context context = FridaApp.getInstance();
        switch (code) {
            case FlashCallback.ERROR_TIMEOUT:
                return context.getString(R.string.flash_error_timeout);

            case FlashCallback.ERROR_SHELL_DIED:
                return context.getString(R.string.flash_error_shell_died);

            case FlashCallback.ERROR_NO_ROOT_ACCESS:
                return context.getString(R.string.root_failed);

            case FlashCallback.ERROR_INVALID_ZIP:
                String message = context.getString(R.string.flash_error_invalid_zip);
                if (args.length > 0) {
                    message += "\n" + args[0];
                }
                return message;

            case FlashCallback.ERROR_NOT_FLASHABLE_IN_APP:
                return context.getString(R.string.flash_error_not_flashable_in_app);

            case FlashCallback.ERROR_INSTALLER_NEEDS_UPDATE:
                Resources res = context.getResources();
                return res.getString(R.string.installer_needs_update, res.getString(R.string.app_name));

            default:
                return context.getString(R.string.flash_error_default, code);
        }
    }

    public static void triggerError(FlashCallback callback, int code, Object... args) {
        callback.onError(code, messageForError(code, args));
    }

    public static void closeSilently(ZipFile z) {
        try {
            z.close();
        } catch (IOException ignored) {
        }
    }

    public static void reportMissingFeatures(Set<String> missingFeatures) {
        Log.e(FridaApp.TAG, "Installer version: " + BuildConfig.VERSION_NAME);
        Log.e(FridaApp.TAG, "Missing installer features: " + missingFeatures);
    }

    private InstallZipUtil() {
    }
}

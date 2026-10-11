package com.irfandev.ssguard;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * SystemServer Guard
 *
 * Crash yang ditangani:
 *   java.lang.NullPointerException: Attempt to invoke virtual method
 *   'int com.android.server.am.HostingRecord.getDefiningUid()' on a null object reference
 *   at ProcessList.removeProcessFromAppZygoteLocked
 *   at ProcessList.removeProcessNameLocked
 *   at ActivityManagerService.cleanUpApplicationRecordLocked
 *   at ...killProcessesForRemovedTask
 *
 * Cara kerja: NPE dari method itu ditangkap di sisi pemanggil (after-hook),
 * lalu method dianggap selesai normal. Method asli tetap berjalan penuh,
 * jadi bagian pembersihan sebelum titik NPE tetap dikerjakan.
 *
 * Scope LSPosed: hanya "System Framework" (android).
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "[SSGuard] ";
    private static final String TARGET_CLASS = "com.android.server.am.ProcessList";
    // dicocokkan dengan contains() supaya tahan terhadap variasi nama/overload antar ROM
    private static final String TARGET_METHOD = "removeProcessFromAppZygote";

    private static final AtomicInteger SWALLOWED = new AtomicInteger();
    private static boolean installed = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"android".equals(lpparam.packageName)) {
            return;
        }
        synchronized (MainHook.class) {
            if (installed) {
                return;
            }
            installed = true;
        }

        try {
            Class<?> processList = lpparam.classLoader.loadClass(TARGET_CLASS);
            int hooked = 0;
            for (Method m : processList.getDeclaredMethods()) {
                if (!m.getName().contains(TARGET_METHOD)) {
                    continue;
                }
                XposedBridge.hookMethod(m, new Guard());
                hooked++;
                XposedBridge.log(TAG + "hook terpasang: " + m);
            }
            if (hooked == 0) {
                XposedBridge.log(TAG + "PERINGATAN: method " + TARGET_METHOD
                        + "* tidak ditemukan di " + TARGET_CLASS
                        + ". Proteksi TIDAK aktif (nama method beda di ROM ini).");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "gagal memasang hook: " + t);
        }
    }

    private static final class Guard extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            Throwable t = param.getThrowable();
            if (t instanceof NullPointerException) {
                // setResult() juga menghapus throwable, jadi system_server tidak crash
                param.setResult(null);
                XposedBridge.log(TAG + "NPE ditangkap (#" + SWALLOWED.incrementAndGet()
                        + "), system_server selamat: " + t.getMessage());
            }
        }
    }
}

package com.dshmobile.app;

import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Installs the four dsh-mobile session cookie aliases before the WebView loads. */
final class SessionCookies {

    private SessionCookies() {
    }

    static List<String> cookies(NativeAuthClient.Session session) {
        String sessionValue = "=" + session.sessionToken
                + "; Path=/; Secure; HttpOnly; SameSite=Strict";
        String csrfValue = "=" + session.csrfToken
                + "; Path=/; Secure; SameSite=Strict";
        List<String> cookies = new ArrayList<>(4);
        cookies.add("dsh_ma_session" + sessionValue);
        cookies.add("dsh_ma_csrf" + csrfValue);
        cookies.add("__Host-dsh_ma_session" + sessionValue);
        cookies.add("__Host-dsh_ma_csrf" + csrfValue);
        return cookies;
    }

    static void install(
            String origin,
            NativeAuthClient.Session session,
            Runnable onSuccess,
            Runnable onFailure) {
        Handler main = new Handler(Looper.getMainLooper());
        List<String> values = cookies(session);
        AtomicInteger remaining = new AtomicInteger(values.size());
        AtomicBoolean settled = new AtomicBoolean(false);
        AtomicBoolean failed = new AtomicBoolean(false);
        Runnable finish = () -> {
            if (!settled.compareAndSet(false, true)) {
                return;
            }
            try {
                CookieManager.getInstance().flush();
            } catch (Exception e) {
                failed.set(true);
            }
            main.post(failed.get() ? onFailure : onSuccess);
        };
        main.post(() -> {
            CookieManager manager = CookieManager.getInstance();
            manager.setAcceptCookie(true);
            for (String cookie : values) {
                try {
                    manager.setCookie(origin, cookie, accepted -> {
                        if (!accepted) {
                            failed.set(true);
                        }
                        if (remaining.decrementAndGet() == 0) {
                            finish.run();
                        }
                    });
                } catch (Exception e) {
                    failed.set(true);
                    if (remaining.decrementAndGet() == 0) {
                        finish.run();
                    }
                }
            }
            main.postDelayed(() -> {
                if (remaining.get() > 0) {
                    failed.set(true);
                    finish.run();
                }
            }, 2500);
        });
    }
}

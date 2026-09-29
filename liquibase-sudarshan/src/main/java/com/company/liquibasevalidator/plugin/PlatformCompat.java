package com.company.liquibasevalidator.plugin;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Computable;

/**
 * Platform calls that must bind to an API present — and NOT deprecated — on every supported
 * build, from the 2021.2 floor to the newest EAP (the Marketplace verifier reports every
 * deprecated usage against each new IDE).
 * <p>
 * Java, not Kotlin, on purpose: from Kotlin, {@code CredentialAttributes(name)} compiles to the
 * Kotlin default-arguments synthetic constructor (deprecated since 251), because the plain
 * {@code (String)} overload is a {@code @JvmOverloads} bridge Kotlin cannot see. Java binds
 * to {@code CredentialAttributes(String)} exactly.
 */
public final class PlatformCompat {

    private PlatformCompat() {
    }

    public static CredentialAttributes credentialAttributes(String serviceName) {
        return new CredentialAttributes(serviceName);
    }

    /**
     * Runs {@code action} under the read lock. Replaces {@code ReadAction.compute} (deprecated
     * in 262 in favour of {@code computeBlocking}, which older builds lack).
     */
    public static <T> T readAction(Computable<T> action) {
        return ApplicationManager.getApplication().runReadAction(action);
    }
}

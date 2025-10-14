package org.insilications.openinsplit;

import com.intellij.openapi.diagnostic.Logger;

import org.jetbrains.annotations.NotNull;

import java.util.function.Supplier;

public final class utils {
    @SuppressWarnings("ImplicitCallToSuper")
    private utils() {
    } // Prevent instantiation

    public static void debug(@NotNull Logger logger, @NotNull Supplier<String> messageSupplier) {
        if (logger.isDebugEnabled()) {
            logger.debug(messageSupplier.get());
        }
    }
}

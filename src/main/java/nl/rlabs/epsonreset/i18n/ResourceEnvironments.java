package nl.rlabs.epsonreset.i18n;

import java.util.Locale;
import org.jetbrains.compose.resources.DensityQualifier;
import org.jetbrains.compose.resources.LanguageQualifier;
import org.jetbrains.compose.resources.RegionQualifier;
import org.jetbrains.compose.resources.ResourceEnvironment;
import org.jetbrains.compose.resources.ThemeQualifier;

/**
 * Builds a {@link ResourceEnvironment} from a locale alone.
 *
 * <p>Compose's own {@code getSystemResourceEnvironment()} asks AWT for the screen resolution, which
 * throws {@code HeadlessException} where there is no display — the test JVM, CI, and the
 * {@code diagnose} and {@code restore} command-line tools on a server. Strings are chosen by locale
 * only: one item per key per locale here, so the theme and density this passes are never consulted.
 *
 * <p>This is Java because the constructors are {@code internal} to Kotlin callers but public in the
 * bytecode. Kotlin can reach them with an INVISIBLE_REFERENCE suppression, but the compiler warns
 * that such behaviour is unspecified; from Java it is ordinary, and still breaks the build if
 * Compose changes the signatures, which reflection would not.
 */
public final class ResourceEnvironments {

    private ResourceEnvironments() {
    }

    public static ResourceEnvironment forLocale(Locale locale) {
        return new ResourceEnvironment(
                new LanguageQualifier(locale.getLanguage()),
                new RegionQualifier(locale.getCountry()),
                ThemeQualifier.LIGHT,
                DensityQualifier.MDPI);
    }
}

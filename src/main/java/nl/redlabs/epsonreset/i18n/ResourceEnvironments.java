package nl.redlabs.epsonreset.i18n;

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
 * only: a key resolves to one item per locale here, so the theme and density this passes are never
 * consulted.
 *
 * <p>This is Java because the constructors are {@code internal} to Kotlin callers but public in the
 * bytecode. That couples us to the library's internals, deliberately: it breaks at compile time if
 * they change, which reflection would not.
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

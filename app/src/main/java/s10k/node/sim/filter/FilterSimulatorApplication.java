
package s10k.node.sim.filter;

import java.util.Map;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.NativeDetector;
import s10k.node.sim.filter.aot.InterfaceInitializer;

@SpringBootApplication
public class FilterSimulatorApplication {

	/**
	 * The web server port the native executable uses by default.
	 */
	public static final int NATIVE_DEFAULT_PORT = 2008;

	public static void main(String[] args) {
		InterfaceInitializer.initialize();
		SpringApplication app = new SpringApplication(FilterSimulatorApplication.class);
		// StartupMessage prints the application URL instead
		app.setBannerMode(Banner.Mode.OFF);
		if ( NativeDetector.inNativeImage() ) {
			// default properties have the lowest precedence, so --server.port,
			// --logging.level.root, and the like still override these; the root level
			// stays at WARN, not OFF, as FilterLogCapture needs filter warnings logged
			app.setDefaultProperties(
					Map.of("server.port", NATIVE_DEFAULT_PORT, "logging.level.root", "WARN"));
		}
		app.run(args);
	}

}

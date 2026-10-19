
package s10k.node.sim.filter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import s10k.node.sim.filter.aot.InterfaceInitializer;

@SpringBootApplication
public class FilterSimulatorApplication {

	public static void main(String[] args) {
		InterfaceInitializer.initialize();
		SpringApplication.run(FilterSimulatorApplication.class, args);
	}

}

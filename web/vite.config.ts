import { defineConfig } from "vite";

export default defineConfig({
	root: "src/main",
	base: "./",
	build: {
		// the Gradle build packages this into the app as static resources
		outDir: "../../build/dist",
		emptyOutDir: true,
	},
	server: {
		port: 5173,
		strictPort: true,
		proxy: {
			// the Spring Boot app, started with `./gradlew :app:bootRun`
			"/api": "http://localhost:8080",
		},
	},
	// Silence Sass deprecation warnings (Bootstrap).
	// See https://getbootstrap.com/docs/5.3/getting-started/vite/#configure-vite
	css: {
		preprocessorOptions: {
			scss: {
				silenceDeprecations: [
					"if-function",
					"import",
					"color-functions",
					"global-builtin",
				],
			},
		},
	},
});

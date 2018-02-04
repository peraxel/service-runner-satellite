
package se.felth.service.starter.satellite;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.ejb.Singleton;
import javax.ejb.Startup;
import javax.ejb.Timeout;
import javax.ejb.TimerConfig;
import javax.ejb.TimerService;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonString;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import org.glassfish.jersey.jackson.JacksonFeature;

/**
 *
 * @author pa
 */
@Singleton
@Startup
public class Runner {

	private static final Logger LOG = Logger.getLogger(Runner.class.getName());

	@Resource(name = "props/service-runner-satellite")
	Properties srsProps;
	
	@Resource
	TimerService ts;
	
	private WebTarget target;

	@PostConstruct
	public void init() {
		ts.createIntervalTimer(2000L, 60000L, new TimerConfig("", false));
		LOG.info("Creating central target for base URL " + srsProps.getProperty("central-url"));
		target = ClientBuilder.newClient().register(JacksonFeature.class).target(srsProps.getProperty("central-url"));
	}
	
	public Path getDeploymentArtifact(String deploymentId) throws IOException {
		byte[] artifact = target.path("server-deployments").path(deploymentId).path("artifact").request().get(byte[].class);
		Path p = Paths.get("/tmp", deploymentId + ".war");
		Files.write(p, artifact);
		
		return p;
	}
	
	public Path getLibraryArtifact(String libraryId) throws IOException {
		byte[] artifact = target.path("libraries").path(libraryId).path("artifact").request().get(byte[].class);
		Path p = Paths.get("/tmp", libraryId + ".jar");
		Files.write(p, artifact);
		
		return p;
	}

	@Timeout
	public void run() {
		JsonArray deployments = target.path("server-deployments").queryParam("server", srsProps.get("this-server-id")).request().get(JsonArray.class);
		List<String> deploymentNames = deployments.stream().map(d -> (JsonObject) d).map(d -> d.getString("name")).collect(Collectors.toList());

		try {
			List<RunningDeployment> running = getRunningDeployments();
			List<String> runningDeploymentNames = running.stream().map(RunningDeployment::getDeploymentName).collect(Collectors.toList());

			for (RunningDeployment rd : running) {
				if (!deploymentNames.contains(rd.getDeploymentName())) {
					kill(rd.getPid());
				}
			}

			for (JsonObject deployment : deployments.getValuesAs(JsonObject.class)) {
				if (!runningDeploymentNames.contains(deployment.getString("name"))) {
					start(deployment);
				}
			}

		} catch (Exception ex) {
			Logger.getLogger(Runner.class.getName()).log(Level.SEVERE, null, ex);
		}
	}

	public List<RunningDeployment> getRunningDeployments() throws Exception {
		Process process = Runtime.getRuntime().exec("ps -e -o pid,command");
		BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream()));
		String line = null;
		Pattern p = Pattern.compile("^.*-Dse.felth.deployment=(\\S+) -jar.*$");

		List<RunningDeployment> deployments = new ArrayList<>();

		while ((line = r.readLine()) != null) {

			String[] split = line.split("\\s+", 2);

			if (line.contains("java") && split.length >= 2) {
				Matcher m = p.matcher(split[1]);
				
				if (m.matches()) {
					deployments.add(new RunningDeployment(split[0], m.group(1)));
				}
			}

		}

		LOG.info(deployments.toString());
		return deployments;
	}

	private void kill(String pid) {
		try {
			LOG.info("Killing pid " + pid);
			Process process = Runtime.getRuntime().exec("kill " + pid);
		} catch (IOException ex) {
			LOG.log(Level.SEVERE, "Failed to kill pid " + pid, ex);
		}
	}

	private void start(JsonObject deployment) {
		LOG.info(deployment.toString());
		try {
			String deploymentName = deployment.getString("name");
			LOG.info("Starting " + deploymentName);
			Path artifact = getDeploymentArtifact(deploymentName);
			List<String> libraryArtifacts = new ArrayList<>();
			
			if (!deployment.isNull("libraryIds")) {
				for(JsonString lid : deployment.getJsonArray("libraryIds").getValuesAs(JsonString.class)) {
					libraryArtifacts.add(getLibraryArtifact(lid.getString()).toString());
				}
			}
			
			String addJars = "";
			if (!libraryArtifacts.isEmpty()) {
				addJars = "--addJars " + String.join(":", libraryArtifacts);
			}
			
			String httpPort = "--port " + deployment.getInt("httpPort");
			String httpsPort = "--sslport " + deployment.getInt("httpsPort");
			
			String prebootCommandsFileString = "";
			
			if (!deployment.isNull("serviceProperties")) {
				JsonObject sProps = deployment.getJsonObject("serviceProperties");
				Map<String,String> props = new HashMap<>();
				sProps.getJsonObject("properties").forEach((k,v) -> props.put(k, ((JsonString)v).getString()));
				
				String r1 = getResourceAddXml(getCustomPropertiesResourceXml(sProps.getString("jndiName"), props));
				Path resourceAddFile = Paths.get("/tmp", UUID.randomUUID().toString());
				Files.write(resourceAddFile, r1.getBytes());
				
				String prebootCommands = "add-resources " + resourceAddFile.toString() + "\n";
				
				Path prebootCommandsFile = Paths.get("/tmp", UUID.randomUUID().toString());
				Files.write(prebootCommandsFile, prebootCommands.getBytes());
				prebootCommandsFileString = "--prebootcommandfile " + prebootCommandsFile.toString();
			}
			
			String command = String.format("/usr/bin/java -Xmx256m -Xms256m -Dse.felth.deployment=%s -jar %s --deploy %s %s --logtofile /tmp/%s.log %s %s %s", deploymentName, srsProps.get("payara-micro-path"), artifact.toString(), addJars, deploymentName, httpPort, httpsPort, prebootCommandsFileString);
			LOG.info(command);
			Process process = Runtime.getRuntime().exec(command);
			
			LOG.info("Started");
			
		} catch (IOException ex) {
			LOG.log(Level.SEVERE, "Failed to start service", ex);
		}
	}
	
	private String getResourceAddTemplate() {
		return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
"<!DOCTYPE resources PUBLIC \n" +
"   \"-//GlassFish.org//DTD GlassFish Application Server 3.1 Resource Definitions //EN\" \n" +
"   \"http://glassfish.org/dtds/glassfish-resources_1_5.dtd\">\n" +
"<resources>\n" +
"  #RESOURCES#\n" +
"</resources>";
	}
	
	private String getCustomPropertiesResourceTemplate() {
		return "<custom-resource factory-class=\"org.glassfish.resources.custom.factory.PropertiesFactory\" res-type=\"java.util.Properties\" jndi-name=\"#JNDINAME#\">\n" +
"#PROPERTIES#" +
"    </custom-resource>";
	}
	
	private String getCustomPropertyTemplate() {
		return "<property name=\"#NAME#\" value=\"#VALUE#\"></property>";
	}
	
	private String getCustomPropertyXml(String name, String value) {
		return getCustomPropertyTemplate().replace("#NAME#", name).replace("#VALUE#", value);
	}
	
	private String getCustomPropertiesResourceXml(String jndiName, Map<String,String> properties) {
		return getCustomPropertiesResourceTemplate().replace("#JNDINAME#", jndiName).replace("#PROPERTIES#", properties.entrySet().stream().map(e -> getCustomPropertyXml(e.getKey(), e.getValue())).collect(Collectors.joining("\n")));
		
	}
	
	private String getResourceAddXml(List<String> resources) {
		return getResourceAddTemplate().replace("#RESOURCES#", String.join("\n", resources));
	}
	
	private String getResourceAddXml(String... resources) {
		return getResourceAddTemplate().replace("#RESOURCES#", String.join("\n", resources));
	}
}

package se.felth.service.starter.satellite;

/**
 *
 * @author pa
 */
public class RunningDeployment {
	String pid;
	String deploymentName;

	public RunningDeployment() {
	}

	public RunningDeployment(String pid, String deploymentName) {
		this.pid = pid;
		this.deploymentName = deploymentName;
	}

	public String getPid() {
		return pid;
	}

	public void setPid(String pid) {
		this.pid = pid;
	}

	public String getDeploymentName() {
		return deploymentName;
	}

	public void setDeploymentName(String deploymentName) {
		this.deploymentName = deploymentName;
	}

    @Override
    public String toString() {
        return "RunningDeployment{" + "pid=" + pid + ", deploymentName=" + deploymentName + '}';
    }
	
	
}

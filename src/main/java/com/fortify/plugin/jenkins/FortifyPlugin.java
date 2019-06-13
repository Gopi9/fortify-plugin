/*******************************************************************************
 * (c) Copyright 2019 Micro Focus or one of its affiliates. 
 * 
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * https://opensource.org/licenses/MIT
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package com.fortify.plugin.jenkins;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fortify.plugin.jenkins.bean.SensorPoolBean;
import com.fortify.plugin.jenkins.steps.*;
import com.fortify.plugin.jenkins.steps.remote.Gradle;
import com.fortify.plugin.jenkins.steps.remote.Maven;
import com.fortify.plugin.jenkins.steps.remote.RemoteAnalysisProjectType;
import com.squareup.okhttp.*;
import hudson.*;
import hudson.model.*;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.*;
import org.kohsuke.stapler.verb.POST;

import com.fortify.plugin.jenkins.bean.ProjectTemplateBean;
import com.fortify.plugin.jenkins.fortifyclient.FortifyClient;
import com.fortify.plugin.jenkins.fortifyclient.FortifyClient.NoReturn;
import com.fortify.plugin.jenkins.steps.types.AdvancedScanType;
import com.fortify.plugin.jenkins.steps.types.DevenvScanType;
import com.fortify.plugin.jenkins.steps.types.DotnetSourceScanType;
import com.fortify.plugin.jenkins.steps.types.GradleScanType;
import com.fortify.plugin.jenkins.steps.types.JavaScanType;
import com.fortify.plugin.jenkins.steps.types.MavenScanType;
import com.fortify.plugin.jenkins.steps.types.MsbuildScanType;
import com.fortify.plugin.jenkins.steps.types.OtherScanType;
import com.fortify.plugin.jenkins.steps.types.ProjectScanType;
import com.fortify.ssc.restclient.ApiException;

import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.util.ComboBoxModel;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import jenkins.model.Jenkins;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;

/**
 * Fortify Jenkins plugin to work with Fortify Software Security Center and
 * Fortify Static Code Analyzer
 * 
 * <p>
 * Main plugin functionality:
 * <ul>
 * <li>Provide pipeline and other means to launch Fortify Static Code Analysis
 * (SCA) as part of the build</li>
 * <li>Upload the resulting FPR analysis file to Fortify Software Security
 * Center (SSC) server</li>
 * <li>Calculate NVS from the results collected from SSC and plot graph on the
 * project main page</li>
 * <li>Make a build to be UNSTABLE if some critical vulnerabilities are found
 * (or based on other info from SSC)</li>
 * <li>Display detailed list of vulnerabilities collected from SSC and provide
 * remediation links</li>
 * </ul>
 *
 */
public class FortifyPlugin extends Recorder {
	private static String pluginVersion;

	public static String getPluginVersion() {
		if (pluginVersion == null) {
			Plugin plugin = Jenkins.get().getPlugin("fortify");
			if (plugin != null) {
				pluginVersion = plugin.getWrapper().getVersion();
			}
		}
		return pluginVersion;
	}

	private static Object syncObj = new Object();

	public static final int DEFAULT_PAGE_SIZE = 50;

	private transient UploadSSCBlock uploadSSC;
	private transient RunTranslationBlock runTranslation;
	private transient RunScanBlock runScan;
	private transient UpdateContentBlock updateContent;
	private transient boolean runSCAClean;
	private transient String buildId;
	private transient String scanFile;
	private transient String maxHeap;
	private transient String addJVMOptions;

	private AnalysisRunType analysisRunType;
	private RemoteAnalysisProjectType remoteAnalysisProjectType;
	private ProjectScanType projectScanType;

	@DataBoundConstructor
	public FortifyPlugin(AnalysisRunType analysisRunType) {
		this.analysisRunType = analysisRunType;
	}

	@Deprecated
	public FortifyPlugin(String buildId, String scanFile, String maxHeap, String addJVMOptions,
			UpdateContentBlock updateContent, boolean runSCAClean, RunTranslationBlock runTranslation,
			RunScanBlock runScan, UploadSSCBlock uploadSSC) {
		this.buildId = buildId;
		this.scanFile = scanFile;
		this.maxHeap = maxHeap;
		this.addJVMOptions = addJVMOptions;
		this.updateContent = updateContent;
		this.runSCAClean = runSCAClean;
		this.runTranslation = runTranslation;
		this.runScan = runScan;
		this.uploadSSC = uploadSSC;
	}

	/* for backwards compatibility */
	protected Object readResolve() {
		if (runTranslation != null) {
			analysisRunType = new AnalysisRunType("local");
			if (updateContent != null) {
				analysisRunType.setUpdateContent(updateContent);
			}
			analysisRunType.setRunSCAClean(runSCAClean);

			if (buildId != null) {
				analysisRunType.setBuildId(buildId);
			}
			if (scanFile != null) {
				analysisRunType.setScanFile(scanFile);
			}
			if (maxHeap != null) {
				analysisRunType.setMaxHeap(maxHeap);
			}
			if (addJVMOptions != null) {
				analysisRunType.setAddJVMOptions(addJVMOptions);
			}

			analysisRunType.setTranslationDebug(runTranslation.getTranslationDebug());
			analysisRunType.setTranslationVerbose(runTranslation.getTranslationVerbose());
			analysisRunType.setTranslationLogFile(runTranslation.getTranslationLogFile());
			analysisRunType.setTranslationExcludeList(runTranslation.getTranslationExcludeList());

			ProjectScanType scanType = null;
			if (runTranslation.isAdvancedTranslationType()) {
				scanType = new AdvancedScanType();
				((AdvancedScanType)scanType).setAdvOptions(runTranslation.getTranslationOptions());
			}

			if (runTranslation.isBasicMaven3TranslationType()) {
				scanType = new MavenScanType();
				((MavenScanType)scanType).setMavenOptions(runTranslation.getMaven3Options());
			}

			if (runTranslation.isBasicGradleTranslationType()) {
				scanType = new GradleScanType();
				((GradleScanType)scanType).setUseWrapper(runTranslation.getGradleUseWrapper());
				((GradleScanType)scanType).setGradleTasks(runTranslation.getGradleTasks());
				((GradleScanType)scanType).setGradleOptions(runTranslation.getGradleOptions());
			}

			if (runTranslation.isBasicJavaTranslationType()) {
				scanType = new JavaScanType();
				((JavaScanType)scanType).setJavaVersion(runTranslation.getTranslationJavaVersion());
				((JavaScanType)scanType).setJavaClasspath(runTranslation.getTranslationClasspath());
				((JavaScanType)scanType).setJavaSrcFiles(runTranslation.getTranslationSourceFiles());
				((JavaScanType)scanType).setJavaAddOptions(runTranslation.getTranslationAddOptions());
			}

			if (runTranslation.isBasicDotNetDevenvBuildType()) {
				scanType = new DevenvScanType();
				((DevenvScanType)scanType).setDotnetProject(runTranslation.getDotNetDevenvProjects());
				((DevenvScanType)scanType).setDotnetAddOptions(runTranslation.getDotNetDevenvAddOptions());
			}

			if (runTranslation.isBasicDotNetMSBuildBuildType()) {
				scanType = new MsbuildScanType();
				((MsbuildScanType)scanType).setDotnetProject(runTranslation.getDotNetMSBuildProjects());
				((MsbuildScanType)scanType).setDotnetAddOptions(runTranslation.getDotNetMSBuildAddOptions());
			}

			if (runTranslation.isBasicDotNetSourceCodeScanType()) {
				scanType = new DotnetSourceScanType();
				((DotnetSourceScanType)scanType).setDotnetFrameworkVersion(runTranslation.getDotNetSourceCodeFrameworkVersion());
				((DotnetSourceScanType)scanType).setDotnetLibdirs(runTranslation.getDotNetSourceCodeLibdirs());
				((DotnetSourceScanType)scanType).setDotnetSrcFiles(runTranslation.getDotNetSourceCodeSrcFiles());
				((DotnetSourceScanType)scanType).setDotnetAddOptions(runTranslation.getDotNetSourceCodeAddOptions());
			}

			if (runTranslation.isBasicOtherTranslationType()) {
				scanType = new OtherScanType();
				((OtherScanType)scanType).setOtherIncludesList(runTranslation.getOtherIncludesList());
				((OtherScanType)scanType).setOtherOptions(runTranslation.getOtherOptions());
			}

			if (scanType != null) {
				analysisRunType.setProjectScanType(scanType);
			}

		}
		if (runScan != null) {
			analysisRunType.setRunScan(runScan);
		}
		if (uploadSSC != null) {
			analysisRunType.setUploadSSC(uploadSSC);
		}
		return this;
	}

	public boolean getAnalysisRunType() { return analysisRunType != null; }

	// populate the dropdown lists
	public RemoteAnalysisProjectType getRemoteAnalysisProjectType() { return getAnalysisRunType() ? analysisRunType.getRemoteAnalysisProjectType() : null; }
	public ProjectScanType getProjectScanType() { return getAnalysisRunType() ? analysisRunType.getProjectScanType() : null; }

	public boolean isRemote() {
		return getAnalysisRunType() && analysisRunType.value.equals("remote");
	}

	public boolean isMixed() {
		return getAnalysisRunType() && analysisRunType.value.equals("mixed");
	}

	public boolean isLocal() {
		return getAnalysisRunType() && analysisRunType.value.equals("local");
	}

	public boolean isTranslationDebug() { return getAnalysisRunType() && analysisRunType.isTranslationDebug(); }
	public boolean isTranslationVerbose() { return getAnalysisRunType() && analysisRunType.isTranslationVerbose(); }

	public String getBuildId() {
		return getAnalysisRunType() ? analysisRunType.getBuildId() : "";
	}

	public String getScanFile() {
		return getAnalysisRunType() ? analysisRunType.getScanFile() : "";
	}

	public String getMaxHeap() {
		return getAnalysisRunType() ? analysisRunType.getMaxHeap() : "";
	}

	public String getAddJVMOptions() {
		return getAnalysisRunType() ? analysisRunType.getAddJVMOptions() : "";
	}

	public boolean getUpdateContent() {
		return getAnalysisRunType() && analysisRunType.getUpdateContent() != null;
	}

	@Deprecated
	public boolean getRunTranslation() {
		return runTranslation != null;
	}

	public boolean getRunScan() {
		return getAnalysisRunType() && analysisRunType.getRunScan() != null;
	}

	public boolean getRunRemoteScan() {
		return getAnalysisRunType() && analysisRunType.getRunRemoteScan() != null;
	}

	public boolean getMbsType() {
		return getRunRemoteScan() && analysisRunType.getRunRemoteScan().getMbsType() != null;
	}

	public boolean isCreate() {
		return getMbsType() && analysisRunType.getRunRemoteScan().getMbsType().getValue().equals("create");
	}

	public boolean isUseExisting() {
		return getMbsType() && analysisRunType.getRunRemoteScan().getMbsType().getValue().equals("useExisting");
	}

	public String getFileName() {
		return isUseExisting() ? analysisRunType.getRunRemoteScan().getMbsType().getFileName() : "";
	}

	public boolean getUploadSSC() {
		return getAnalysisRunType() && analysisRunType.getUploadSSC() != null;
	}

	public String getUpdateServerUrl() {
		return getUpdateContent() ? analysisRunType.getUpdateContent().getUpdateServerUrl() : "";
	}

	@Deprecated
	public boolean getUpdateUseProxy() {
		return getUpdateContent() && updateContent.getUpdateUseProxy();
	}

	@Deprecated
	public String getUpdateProxyUrl() {
		return getUpdateUseProxy() ? updateContent.getUpdateProxyUrl() : "";
	}

	@Deprecated
	public String getUpdateProxyUsername() {
		return getUpdateUseProxy() ? updateContent.getUpdateProxyUsername() : "";
	}

	@Deprecated
	public String getUpdateProxyPassword() {
		return getUpdateUseProxy() ? updateContent.getUpdateProxyPassword() : "";
	}

	public boolean getRunSCAClean() {
		return getAnalysisRunType() && analysisRunType.isRunSCAClean();
	}

	@Deprecated
	public String getTranslationType() {
		return getRunTranslation() ? runTranslation.getTranslationType() : "";
	}

	@Deprecated
	public boolean getIsBasicTranslationType() {
		return getRunTranslation() && runTranslation.isBasicTranslationType();
	}

	@Deprecated
	public boolean getIsAdvancedTranslationType() {
		return getRunTranslation() && runTranslation.isAdvancedTranslationType();
	}

	@Deprecated
	public boolean getIsBasicJavaTranslationType() {
		return getRunTranslation() && runTranslation.isBasicJavaTranslationType();
	}

	@Deprecated
	public boolean getIsBasicDotNetTranslationType() {
		return getRunTranslation() && runTranslation.isBasicDotNetTranslationType();
	}

	@Deprecated
	public boolean getIsBasicMaven3TranslationType() {
		return getRunTranslation() && runTranslation.isBasicMaven3TranslationType();
	}

	@Deprecated
	public boolean getIsBasicGradleTranslationType() {
		return getRunTranslation() && runTranslation.isBasicGradleTranslationType();
	}

	@Deprecated
	public boolean getIsBasicOtherTranslationType() {
		return getRunTranslation() && runTranslation.isBasicOtherTranslationType();
	}

	@Deprecated
	public String getTranslationJavaVersion() {
		return getRunTranslation() ? runTranslation.getTranslationJavaVersion() : "";
	}

	@Deprecated
	public String getTranslationJavaClasspath() {
		return getRunTranslation() ? runTranslation.getTranslationClasspath() : "";
	}

	@Deprecated
	public String getTranslationJavaSourceFiles() {
		return getRunTranslation() ? runTranslation.getTranslationSourceFiles() : "";
	}

	@Deprecated
	public String getTranslationJavaAddOptions() {
		return getRunTranslation() ? runTranslation.getTranslationAddOptions() : "";
	}

	public String getTranslationExcludeList() {
		return getAnalysisRunType() ? analysisRunType.getTranslationExcludeList() : "";
	}

	@Deprecated
	public String getTranslationOptions() {
		return getRunTranslation() ? runTranslation.getTranslationOptions() : "";
	}

	@Deprecated
	public boolean getTranslationDebug() {
		return getRunTranslation() && runTranslation.getTranslationDebug();
	}

	@Deprecated
	public boolean getTranslationVerbose() {
		return getRunTranslation() && runTranslation.getTranslationVerbose();
	}

	public String getTranslationLogFile() {
		return getAnalysisRunType() ? analysisRunType.getTranslationLogFile() : "";
	}

	@Deprecated
	public boolean getIsBasicDotNetProjectSolutionScanType() {
		return getRunTranslation() && runTranslation.isBasicDotNetProjectSolutionScanType();
	}

	@Deprecated
	public boolean getIsBasicDotNetSourceCodeScanType() {
		return getRunTranslation() && runTranslation.isBasicDotNetSourceCodeScanType();
	}

	@Deprecated
	public boolean getIsBasicDotNetDevenvBuildType() {
		return getRunTranslation() && runTranslation.isBasicDotNetDevenvBuildType();
	}

	@Deprecated
	public boolean getIsBasicDotNetMSBuildBuildType() {
		return getRunTranslation() && runTranslation.isBasicDotNetMSBuildBuildType();
	}

	@Deprecated
	public String getDotNetDevenvProjects() {
		return getRunTranslation() ? runTranslation.getDotNetDevenvProjects() : "";
	}

	@Deprecated
	public String getDotNetDevenvAddOptions() {
		return getRunTranslation() ? runTranslation.getDotNetDevenvAddOptions() : "";
	}

	@Deprecated
	public String getDotNetMSBuildProjects() {
		return getRunTranslation() ? runTranslation.getDotNetMSBuildProjects() : "";
	}

	@Deprecated
	public String getDotNetMSBuildAddOptions() {
		return getRunTranslation() ? runTranslation.getDotNetMSBuildAddOptions() : "";
	}

	@Deprecated
	public String getDotNetSourceCodeFrameworkVersion() {
		return getRunTranslation() ? runTranslation.getDotNetSourceCodeFrameworkVersion() : "";
	}

	@Deprecated
	public String getDotNetSourceCodeLibdirs() {
		return getRunTranslation() ? runTranslation.getDotNetSourceCodeLibdirs() : "";
	}

	@Deprecated
	public String getDotNetSourceCodeAddOptions() {
		return getRunTranslation() ? runTranslation.getDotNetSourceCodeAddOptions() : "";
	}

	@Deprecated
	public String getDotNetSourceCodeSrcFiles() {
		return getRunTranslation() ? runTranslation.getDotNetSourceCodeSrcFiles() : "";
	}

	@Deprecated
	public String getMaven3Options() {
		return getRunTranslation() ? runTranslation.getMaven3Options() : "";
	}

	@Deprecated
	public boolean getGradleUseWrapper() {
		return getRunTranslation() && runTranslation.getGradleUseWrapper();
	}

	@Deprecated
	public String getGradleTasks() {
		return getRunTranslation() ? runTranslation.getGradleTasks() : "";
	}

	@Deprecated
	public String getGradleOptions() {
		return getRunTranslation() ? runTranslation.getGradleOptions() : "";
	}

	@Deprecated
	public String getOtherOptions() {
		return getRunTranslation() ? runTranslation.getOtherOptions() : "";
	}

	@Deprecated
	public String getOtherIncludesList() {
		return getRunTranslation() ? runTranslation.getOtherIncludesList() : "";
	}

	public String getScanCustomRulepacks() {
		return getRunScan() ? analysisRunType.getRunScan().getScanCustomRulepacks() : "";
	}

	public String getScanAddOptions() {
		return getRunScan() ? analysisRunType.getRunScan().getScanAddOptions() : "";
	}

	public boolean getScanDebug() {
		return getRunScan() && analysisRunType.getRunScan().getScanDebug();
	}

	public boolean getScanVerbose() {
		return getRunScan() && analysisRunType.getRunScan().getScanVerbose();
	}

	public String getScanLogFile() {
		return getRunScan() ? analysisRunType.getRunScan().getScanLogFile() : "";
	}

	// these are the original fields for uploading to ssc - don't feel like renaming
	// them...
	public String getFilterSet() {
		return getUploadSSC() ? analysisRunType.getUploadSSC().getFilterSet() : "";
	}

	public String getSearchCondition() {
		return getUploadSSC() ? analysisRunType.getUploadSSC().getSearchCondition() : "";
	}

	public String getProjectName() {
		return getUploadSSC() ? analysisRunType.getUploadSSC().getProjectName() : "";
	}

	public String getProjectVersion() {
		return getUploadSSC() ? analysisRunType.getUploadSSC().getProjectVersion() : "";
	}

	@Deprecated
	public String getUploadWaitTime() {
		return uploadSSC == null ? null : uploadSSC.getPollingInterval();
	}

	public String getPollingInterval() {
		return getUploadSSC() ? analysisRunType.getUploadSSC().getPollingInterval() : "";
	}

	public boolean getRemoteOptionalConfig() {
		if (isRemote()) {
			return analysisRunType.getRemoteOptionalConfig() != null;
		} else if (isMixed()) {
			return getRunRemoteScan() && analysisRunType.getRunRemoteScan().getRemoteOptionalConfig() != null && analysisRunType.getRunRemoteScan().getRemoteOptionalConfig() != null;
		} else {
			return false;
		}
	}

	public String getSensorPoolName() {
		if (isRemote()) {
			return analysisRunType.getRemoteOptionalConfig().getSensorPoolName();
		} else if (isMixed()) {
			return getRunRemoteScan() && analysisRunType.getRunRemoteScan().getRemoteOptionalConfig() != null ? analysisRunType.getRunRemoteScan().getRemoteOptionalConfig().getSensorPoolName() : "";
		} else {
			return "";
		}
	}

	public String getEmailAddr() {
		if (isRemote()) {
			return analysisRunType.getRemoteOptionalConfig().getEmailAddr();
		} else if (isMixed()) {
			return getRunRemoteScan() && analysisRunType.getRunRemoteScan().getRemoteOptionalConfig() != null ? analysisRunType.getRunRemoteScan().getRemoteOptionalConfig().getEmailAddr() : "";
		} else {
			return "";
		}
	}

	public String getScaScanOptions() {
		if (isRemote()) {
			return analysisRunType.getRemoteOptionalConfig().getScaScanOptions();
		} else if (isMixed()) {
			return getRunRemoteScan() && analysisRunType.getRunRemoteScan().getRemoteOptionalConfig() != null ? analysisRunType.getRunRemoteScan().getRemoteOptionalConfig().getScaScanOptions() : "";
		} else {
			return "";
		}
	}

	public String getRulepacks() {
		if (isRemote()) {
			return analysisRunType.getRemoteOptionalConfig().getRulepacks();
		} else if (isMixed()) {
			return getRunRemoteScan() && analysisRunType.getRunRemoteScan().getRemoteOptionalConfig() != null ? analysisRunType.getRunRemoteScan().getRemoteOptionalConfig().getRulepacks() : "";
		} else {
			return "";
		}
	}

	public String getFilterFile() {
		if (isRemote()) {
			return analysisRunType.getRemoteOptionalConfig().getFilterFile();
		} else if (isMixed()) {
			return getRunRemoteScan() && analysisRunType.getRunRemoteScan().getRemoteOptionalConfig() != null ? analysisRunType.getRunRemoteScan().getRemoteOptionalConfig().getFilterFile() : "";
		} else {
			return "";
		}
	}

	public String getBuildTool() {
		if (!getAnalysisRunType()) {
			return "";
		}
		if (getRemoteAnalysisProjectType() instanceof Gradle) {
			return "gradle";
		} else if (getRemoteAnalysisProjectType() instanceof Maven) {
			return "mvn";
		} else {
			return "none";
		}
	}

	public String getBuildFile() {
		if (!getAnalysisRunType()) {
			return "";
		}
		if (getRemoteAnalysisProjectType() instanceof Gradle) {
			return ((Gradle) getRemoteAnalysisProjectType()).getBuildFile();
		} else if (getRemoteAnalysisProjectType() instanceof Maven) {
			return ((Maven) getRemoteAnalysisProjectType()).getBuildFile();
		} else {
			return "";
		}
	}

	public boolean getIncludeTests() {
		if (getAnalysisRunType()) {
			if (getRemoteAnalysisProjectType() instanceof Gradle) {
				return ((Gradle) getRemoteAnalysisProjectType()).getIncludeTests();
			} else if (getRemoteAnalysisProjectType() instanceof Maven) {
				return ((Maven) getRemoteAnalysisProjectType()).getIncludeTests();
			}
		}
		return false;
	}

	public String getTransArgs() {
		return getRemoteAnalysisProjectType() == null ? "" : analysisRunType.getTransArgs();
	}

	public String getMbsFile() {
		return getRunRemoteScan() ? getFileName() : "";
	}
	public String getScanArgs() { return getRemoteOptionalConfig() ? getScaScanOptions() : ""; }

	@Override
	public BuildStepMonitor getRequiredMonitorService() {
		return BuildStepMonitor.NONE;
	}

	/*
	 * https://bugzilla.fortify.swinfra.net/bugzilla/show_bug.cgi?id=49956 It is may
	 * be some bad practice to get current opened Jenkins configuration from that
	 * method, but it is not very easy to get that information from other place. The
	 * main problem is that we should store the build (or project) info between
	 * Jenkins starts. If you know how to correctly get project in Publisher without
	 * some manual configuration saving please change it. We should make Fortify
	 * Assessment action as build action not project.
	 */
	@Override
	public Collection<? extends Action> getProjectActions(AbstractProject<?, ?> project) {
		return Collections.emptyList();
	}

	@Override
	public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
			throws InterruptedException, IOException {
		PrintStream log = listener.getLogger();
		log.println("Fortify Jenkins plugin v " + getPluginVersion());

		if (isRemote()) {
			runRemote(build, launcher, listener);
		} else if (isMixed()) {
			runMixed(build, launcher, listener);
		} else if (isLocal()) { // Local Translation
			runLocal(build, launcher, listener);
		}

		return true;
	}

	private void runRemote(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
		PrintStream log = listener.getLogger();
		log.println("Running remote translation and scan.");

		final RemoteAnalysisProjectType remoteAnalysisProjectType = getRemoteAnalysisProjectType();
		CloudScanStart csStart = new CloudScanStart(remoteAnalysisProjectType);
		CloudScanArguments csArguments = new CloudScanArguments();

		if (getRemoteOptionalConfig()) {
			csStart.setRemoteOptionalConfig(analysisRunType.getRemoteOptionalConfig());

			csArguments.setScanArgs(getScanArgs());
		}

		csArguments.setTransArgs(getTransArgs());

		if (StringUtils.isNotEmpty(csArguments.getTransArgs()) || StringUtils.isNotEmpty(csArguments.getScanArgs())) {
			csArguments.perform(build, launcher, listener);
		}

		if (getUploadSSC()) {
			csStart.setUploadSSC(analysisRunType.getUploadSSC());
		}

		// run CloudScan start command
		csStart.perform(build, launcher, listener);
	}

	private void runMixed(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
		PrintStream log = listener.getLogger();
		log.println("Running local translation and remote scan.");

		if (isCreate()) { // only run the translation if you want to create an MBS
			performLocalTranslation(build, launcher, listener);
		}

		final RemoteAnalysisProjectType remoteAnalysisProjectType = getRemoteAnalysisProjectType();
		CloudScanStart csStart = new CloudScanStart(remoteAnalysisProjectType);

		if (getRemoteOptionalConfig()) {
			csStart.setRemoteOptionalConfig(analysisRunType.getRunRemoteScan().getRemoteOptionalConfig());
		}
		if (isCreate()) {
			csStart.setCreateMbs(true);
			csStart.setBuildID(getBuildId());
		} else if (isUseExisting()) {
			csStart.setCreateMbs(false);
			csStart.setMbsFile(getMbsFile());
		}

		csStart.setScanArgs(getScanArgs());

		if (getUploadSSC()) {
			csStart.setUploadSSC(analysisRunType.getUploadSSC());
		}

		// run CloudScan start command
		csStart.perform(build, launcher, listener);
	}

	private void runLocal(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws IOException, InterruptedException {
		PrintStream log = listener.getLogger();
		log.println("Running local translation and scan.");

		performLocalTranslation(build, launcher, listener);

		if (getRunScan()) {
			FortifyScan fs = new FortifyScan(getBuildId());
			fs.setAddJVMOptions(getAddJVMOptions());
			fs.setMaxHeap(getMaxHeap());
			fs.setDebug(getScanDebug());
			fs.setVerbose(getScanVerbose());
			fs.setLogFile(getScanLogFile());
			fs.setResultsFile(getScanFile());
			fs.setCustomRulepacks(getScanCustomRulepacks());
			fs.setAddOptions(getScanAddOptions());

			fs.perform(build, launcher, listener);
		}

		if (getUploadSSC()) {
			FortifyUpload upload = new FortifyUpload(false, getProjectName(), getProjectVersion());
			upload.setFailureCriteria(getSearchCondition());
			upload.setFilterSet(getFilterSet());
			upload.setResultsFile(getScanFile());
			upload.setPollingInterval(getPollingInterval());

			upload.perform(build, launcher, listener);
		}

	}

	private void performLocalTranslation(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws IOException, InterruptedException {
		// Update security content
		if (getUpdateContent()) {
			FortifyUpdate fu = new FortifyUpdate.Builder().updateServerURL(getUpdateServerUrl()).build();
			fu.perform(build, launcher, listener);
		}
		// run Fortify SCA clean
		if (getRunSCAClean()) {
			FortifyClean fc = new FortifyClean(getBuildId());
			fc.perform(build, launcher, listener);
		}

		final ProjectScanType projectScanType = getProjectScanType();
		if (projectScanType != null) {
			FortifyTranslate ft = new FortifyTranslate(getBuildId(), projectScanType);
			ft.setMaxHeap(getMaxHeap());
			ft.setAddJVMOptions(getAddJVMOptions());
			ft.setDebug(isTranslationDebug());
			ft.setVerbose(isTranslationVerbose());
			ft.setLogFile(getTranslationLogFile());
			ft.setExcludeList(getTranslationExcludeList());

			if (projectScanType instanceof JavaScanType) {
				ft.setJavaVersion(((JavaScanType) projectScanType).getJavaVersion());
				ft.setJavaClasspath(((JavaScanType) projectScanType).getJavaClasspath());
				ft.setJavaSrcFiles(((JavaScanType) projectScanType).getJavaSrcFiles());
				ft.setJavaAddOptions(((JavaScanType) projectScanType).getJavaAddOptions());
			} else if (projectScanType instanceof DevenvScanType) {
				ft.setDotnetProject(((DevenvScanType) projectScanType).getDotnetProject());
				ft.setDotnetAddOptions(((DevenvScanType) projectScanType).getDotnetAddOptions());
			} else if (projectScanType instanceof MsbuildScanType) {
				ft.setDotnetProject(((MsbuildScanType) projectScanType).getDotnetProject());
				ft.setDotnetAddOptions(((MsbuildScanType) projectScanType).getDotnetAddOptions());
			} else if (projectScanType instanceof DotnetSourceScanType) {
				ft.setDotnetFrameworkVersion(((DotnetSourceScanType) projectScanType).getDotnetFrameworkVersion());
				ft.setDotnetLibdirs(((DotnetSourceScanType) projectScanType).getDotnetLibdirs());
				ft.setDotnetAddOptions(((DotnetSourceScanType) projectScanType).getDotnetAddOptions());
				ft.setDotnetSrcFiles(((DotnetSourceScanType) projectScanType).getDotnetSrcFiles());
			} else if (projectScanType instanceof MavenScanType) {
				ft.setMavenOptions(((MavenScanType) projectScanType).getMavenOptions());
			} else if (projectScanType instanceof GradleScanType) {
				ft.setUseWrapper(((GradleScanType) projectScanType).getUseWrapper());
				ft.setGradleTasks(((GradleScanType) projectScanType).getGradleTasks());
				ft.setGradleOptions(((GradleScanType) projectScanType).getGradleOptions());
			} else if (projectScanType instanceof OtherScanType) {
				ft.setOtherIncludesList(((OtherScanType) projectScanType).getOtherIncludesList());
				ft.setOtherOptions(((OtherScanType) projectScanType).getOtherOptions());
			} else if (projectScanType instanceof AdvancedScanType) {
				ft.setAdvOptions(((AdvancedScanType) projectScanType).getAdvOptions());
			}

			ft.perform(build, launcher, listener);
		}
	}

	/**
	 * Determines the {@link ProjectScanType} based on the configuration.
	 */
	private ProjectScanType calculateProjectScanType() {
		if (getIsAdvancedTranslationType()) {
			return new AdvancedScanType();
		} else {
			if (getIsBasicJavaTranslationType()) {
				return new JavaScanType();
			} else if (getIsBasicDotNetTranslationType()) {
				if (getIsBasicDotNetProjectSolutionScanType()) {
					if (getIsBasicDotNetMSBuildBuildType()) {
						return new MsbuildScanType();
					} else {
						return new DevenvScanType();
					}
				} else {
					return new DotnetSourceScanType();
				}
			} else if (getIsBasicMaven3TranslationType()) {
				return new MavenScanType();
			} else if (getIsBasicGradleTranslationType()) {
				return new GradleScanType();
			} else {
				return new OtherScanType();
			}
		}
	}

	private static <T> T runWithFortifyClient(String token, FortifyClient.Command<T> cmd) throws Exception {
		if (cmd != null) {
			String url = DESCRIPTOR.getUrl();
			ClassLoader contextClassLoader = null;
			try {
				FortifyClient client = null;
				synchronized (syncObj) {
					contextClassLoader = Thread.currentThread().getContextClassLoader();
					Thread.currentThread().setContextClassLoader(FortifyPlugin.class.getClassLoader());
					client = new FortifyClient();
					boolean useProxy = DESCRIPTOR.getUseProxy();
					String proxyUrl = DESCRIPTOR.getProxyUrl();
					if (!useProxy || StringUtils.isEmpty(proxyUrl)) {
						client.init(url, token);
					} else {
						String[] proxyUrlSplit = proxyUrl.split(":");
						String proxyHost = proxyUrlSplit[0];
						int proxyPort = 80;
						if (proxyUrlSplit.length > 1) {
							try {
								proxyPort = Integer.parseInt(proxyUrlSplit[1]);
							} catch (NumberFormatException nfe) {
							}
						}
						client.init(url, token, proxyHost, proxyPort, DESCRIPTOR.getProxyUsername(),
								DESCRIPTOR.getProxyPassword());
					}
					/*boolean useProxy = Jenkins.get().proxy != null;
					if (!useProxy) {
						client.init(url, token);
					} else {
						String proxyHost = Jenkins.get().proxy.name;
						int proxyPort = Jenkins.get().proxy.port;
						String proxyUsername = Jenkins.get().proxy.getUserName();
						String proxyPassword = Jenkins.get().proxy.getPassword();
						client.init(url, token, proxyHost, proxyPort, proxyUsername, proxyPassword);
					}*/
				}
				return cmd.runWith(client);
			} finally {
				if (contextClassLoader != null) {
					Thread.currentThread().setContextClassLoader(contextClassLoader);
			}
			}
		}
		return null;
	}

	@Extension
	public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

	public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {

		/** SSC URL, e.g. http://localhost:8080/ssc */
		private String url;

		/** SSC proxy **/
		private boolean useProxy;
		private String proxyUrl; // host:port
		private Secret proxyUsername;
		private Secret proxyPassword;

		/** SSC Authentication Token */
		private Secret token;

		/** SSC issue template name (used during creation of new application version) */
		private String projectTemplate;

		/** Number of issues to be displayed per page in breakdown table */
		private Integer breakdownPageSize;

		/** List of Issue Templates obtained from SSC */
		private List<ProjectTemplateBean> projTemplateList = Collections.emptyList();

		/** List of all Projects (including versions info) obtained from SSC */
		private Map<String, Map<String, Long>> allProjects = Collections.emptyMap();

		/** List of all CloudScan Sensor pools obtained from SSC */
		private List<SensorPoolBean> sensorPoolList = Collections.emptyList();

		/** CloudScan Controller URL */
		private String ctrlUrl;

		/** CloudScan Controller Token*/
		private Secret ctrlToken;

		public DescriptorImpl() {
			super(FortifyPlugin.class);
			load();
		}

		@Override
		public boolean isApplicable(Class<? extends AbstractProject> jobType) {
			return true; // applicable to all application type
		}

		@Override
		public String getDisplayName() {
			return "Fortify Assessment";
		}

		public String getUrl() {
			return url;
		}

		public boolean getUseProxy() {
			return useProxy;
		}

		public String getProxyUrl() {
			return proxyUrl;
		}

		public String getProxyUsername() {
			return proxyUsername == null ? "" : proxyUsername.getPlainText();
		}

		public String getProxyPassword() {
			return proxyPassword == null ? "" : proxyPassword.getPlainText();
		}

		public String getToken() {
			return token == null ? "" : token.getPlainText();
		}

		public boolean canUploadToSsc() {
			return (!StringUtils.isBlank(getUrl()) && !StringUtils.isBlank(getToken()));
		}

		public String getProjectTemplate() {
			return projectTemplate;
		}

		public Integer getBreakdownPageSize() {
			return breakdownPageSize;
		}

		public String getCtrlUrl() { return ctrlUrl; }

		public String getCtrlToken() { return ctrlToken == null ? "" : ctrlToken.getPlainText(); }

		public FormValidation doCheckBreakdownPageSize(@QueryParameter String value) {
			if (StringUtils.isBlank(value)) {
				return FormValidation.ok();
			}

			int pageSize = 0;
			try {
				pageSize = Integer.parseInt(value);
			} catch (NumberFormatException e) {
				return FormValidation.warning("Expected an integer value greater than zero.");
			}
			if (pageSize < 1) {
				return FormValidation.warning("Expected an integer value greater than zero.");
			}
			return FormValidation.ok();
		}

		public FormValidation doCheckUrl(@QueryParameter String value) {
			try {
				checkUrlValue(value.trim());
			} catch (FortifyException e) {
				return FormValidation.warning(e.getMessage());
			}
			return FormValidation.ok();
		}

		public FormValidation doCheckProxyUrl(@QueryParameter String value) {
			try {
				checkProxyUrlValue(value.trim());
			} catch (FortifyException e) {
				return FormValidation.warning(e.getMessage());
			}
			return FormValidation.ok();
		}

		@POST
		public FormValidation doCheckProxyUsername(@QueryParameter String value) {
			try {
				checkProxyUsernameValue(value.trim());
			} catch (FortifyException e) {
				return FormValidation.warning(e.getMessage());
			}
			return FormValidation.ok();
		}

		@POST
		public FormValidation doCheckProxyPassword(@QueryParameter String value) {
			try {
				checkProxyPasswordValue(value);
			} catch (FortifyException e) {
				return FormValidation.warning(e.getMessage());
			}
			return FormValidation.ok();
		}

		@POST
		public FormValidation doCheckToken(@QueryParameter String value) {
			if (StringUtils.isBlank(value)) {
				return FormValidation.warning("Authentication token cannot be empty");
			}
			return FormValidation.ok();
		}

		@POST
		public FormValidation doCheckCtrlToken(@QueryParameter String value) {
			if (StringUtils.isBlank(value)) {
				return FormValidation.warning("Controller token cannot be empty");
			}
			return FormValidation.ok();
		}

		public FormValidation doCheckFpr(@QueryParameter String value) {
			if (StringUtils.isBlank(value) || value.charAt(0) == '$') { // parameterized values are not checkable
				return FormValidation.ok();
			} else if (value.contains("/") || value.contains("\\")
					|| !FilenameUtils.isExtension(value.toLowerCase(), new String[] { "fpr" })) {
				return FormValidation.error("The FPR filename should be in basename *ONLY*, with extension FPR");
			} else {
				return FormValidation.ok();
			}
		}

		public FormValidation doCheckProjectTemplate(@QueryParameter String value) {
			try {
				checkProjectTemplateName(value.trim());
			} catch (FortifyException e) {
				return FormValidation.error(e.getMessage());
			}
			return FormValidation.ok();
		}

		public FormValidation doCheckProjectName(@QueryParameter String value) {
			return FormValidation.ok();
		}

		public FormValidation doCheckProjectVersion(@QueryParameter String value) {
			return FormValidation.ok();
		}

		public FormValidation doCheckPollingInterval(@QueryParameter String value) {
			if (StringUtils.isBlank(value) || value.charAt(0) == '$') {
				return FormValidation.ok();
			} else {
				int x = -1;
				try {
					x = Integer.parseInt(value);
					if (x >= 0 && x <= 60)
						return FormValidation.ok();
				} catch (NumberFormatException e) {
				}
				return FormValidation.error("The unit is in minutes, and in the range of 0 to 60");
			}
		}

		public FormValidation doCheckCtrlUrl(@QueryParameter String value) {
			try {
				checkCtrlUrlValue(value.trim());
			} catch (FortifyException e) {
				return FormValidation.warning(e.getMessage());
			}
			return FormValidation.ok();
		}

		private FormValidation doTestConnection(String url, String token, String jarsPath) {
			return doTestConnection(url, token, jarsPath, this.useProxy, this.proxyUrl, this.getProxyUsername(),
					this.getProxyPassword());
		}

		public FormValidation doTestConnection(@QueryParameter String url, @QueryParameter String token,
				@QueryParameter String jarsPath, @QueryParameter boolean useProxy, @QueryParameter String proxyUrl,
				@QueryParameter String proxyUsername, @QueryParameter String proxyPassword) {
			String sscUrl = url == null ? "" : url.trim();
			try {
				checkUrlValue(sscUrl);
			} catch (FortifyException e) {
				return FormValidation.error(e.getMessage());
			}
			String userToken = token == null ? "" : token.trim();
			if (StringUtils.isBlank(userToken)) {
				return FormValidation.error("Authentication token cannot be empty");
			} else if (userToken.indexOf(' ') != -1) {
				return FormValidation.error("Authentication token should not contain spaces");
			}

			// backup original values
			String orig_url = this.url;
			Secret orig_token = this.token;
			boolean orig_useProxy = this.useProxy;
			String orig_proxyUrl = this.proxyUrl;
			Secret orig_proxyUsername = this.proxyUsername;
			Secret orig_proxyPassword = this.proxyPassword;
			this.url = sscUrl;
			this.token = userToken.isEmpty() ? null : Secret.fromString(userToken);
			this.useProxy = useProxy;
			this.proxyUrl = proxyUrl;
			this.proxyUsername = proxyUsername == null ? null : Secret.fromString(proxyUsername);
			this.proxyPassword = proxyPassword == null ? null : Secret.fromString(proxyPassword);
			try {
				runWithFortifyClient(userToken, new FortifyClient.Command<FortifyClient.NoReturn>() {
					@Override
					public NoReturn runWith(FortifyClient client) throws Exception {
						// as long as no exception, that's ok
						client.getProjectList();
						return FortifyClient.NoReturn.INSTANCE;
					}
				});
				return FormValidation.okWithMarkup("<font color=\"blue\">Connection successful!</font>");
			} catch (Throwable t) {
				if (t.getMessage().contains("Access Denied")) {
					return FormValidation.error(t, "Invalid token");
				}
				return FormValidation.error(t, "Cannot connect to SSC server");
			} finally {
				this.url = orig_url;
				this.token = orig_token;
				this.useProxy = orig_useProxy;
				this.proxyUrl = orig_proxyUrl;
				this.proxyUsername = orig_proxyUsername;
				this.proxyPassword = orig_proxyPassword;
			}
		}

		public FormValidation doTestCtrlConnection(@QueryParameter String ctrlUrl, @QueryParameter boolean useProxy, @QueryParameter String proxyUrl,
												   @QueryParameter String proxyUsername, @QueryParameter String proxyPassword) throws IOException{
			String controllerUrl = ctrlUrl == null ? "" : ctrlUrl.trim();
			try {
				checkUrlValue(controllerUrl);
			} catch (FortifyException e) {
				return FormValidation.error(e.getMessage());
			}

			// backup original values
			String orig_url = this.ctrlUrl;
			boolean orig_useProxy = this.useProxy;
			String orig_proxyUrl = this.proxyUrl;
			Secret orig_proxyUsername = this.proxyUsername;
			Secret orig_proxyPassword = this.proxyPassword;

			this.ctrlUrl = controllerUrl;
			this.useProxy = useProxy;
			this.proxyUrl = proxyUrl;
			this.proxyUsername = proxyUsername == null ? null : Secret.fromString(proxyUsername);
			this.proxyPassword = proxyPassword == null ? null : Secret.fromString(proxyPassword);
			OkHttpClient client;

			String[] proxyUrlSplit = proxyUrl.split(":");
			String proxyHost = proxyUrlSplit[0];
			int proxyPort = 80;
			if (proxyUrlSplit.length > 1) {
				try {
					proxyPort = Integer.parseInt(proxyUrlSplit[1]);
				} catch (NumberFormatException nfe) {
				}
			}

			if (useProxy && proxyHost.length() > 0) {
				client = configureProxy(proxyHost, proxyPort, proxyUsername, proxyPassword);
			} else {
				client = new OkHttpClient();
			}

			Request request = new Request.Builder()
					.url(controllerUrl)
					.build();
			Response response = null;
			try {
				response = client.newCall(request).execute();

				if (response.isSuccessful() && response.body().string().contains("Fortify CloudScan Controller")) {
					return FormValidation.okWithMarkup("<font color=\"blue\">Connection successful!</font>");
				} else {
					return FormValidation.error("Connection failed. Check the Controller URL.");
				}
			} catch (Throwable t) {
				return FormValidation.error(t, "Cannot connect to Controller");
			} finally {
				this.ctrlUrl = orig_url;
				this.useProxy = orig_useProxy;
				this.proxyUrl = orig_proxyUrl;
				this.proxyUsername = orig_proxyUsername;
				this.proxyPassword = orig_proxyPassword;
				if (response != null && response.body() != null) {
					response.body().close();
				}
			}
		}

		private OkHttpClient configureProxy(String host, int port, String user, String pass) {
			int proxyPort = port;
			String proxyHost = host;
			final String username = user;
			final String password = pass;

			Authenticator proxyAuthenticator = new Authenticator() {
				@Override public Request authenticate(Proxy proxy, Response response) throws IOException {
					String credential = Credentials.basic(username, password);
					return response.request().newBuilder()
							.header("Authorization", credential)
							.build();
				}

				@Override
				public Request authenticateProxy(Proxy proxy, Response response) throws IOException {
					String credential = Credentials.basic(username, password);
					return response.request().newBuilder()
							.header("Proxy-Authorization", credential)
							.build();
				}
			};

			OkHttpClient client = new OkHttpClient();
			client.setConnectTimeout(60, TimeUnit.SECONDS);
			client.setWriteTimeout(60, TimeUnit.SECONDS);
			client.setReadTimeout(60, TimeUnit.SECONDS);
			client.setProxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort)));
			client.setAuthenticator(proxyAuthenticator);

			return client;
		}

		private void checkUrlValue(String sscUrl) throws FortifyException {
			if (StringUtils.isBlank(sscUrl)) {
				throw new FortifyException(new Message(Message.ERROR, "SSC URL cannot be empty"));
			} else {
				if (StringUtils.startsWith(sscUrl, "http://") || StringUtils.startsWith(sscUrl, "https://")) {
					if (sscUrl.trim().equalsIgnoreCase("http://") || sscUrl.trim().equalsIgnoreCase("https://")) {
						throw new FortifyException(new Message(Message.ERROR, "URL host is required"));
					}
				} else {
					throw new FortifyException(new Message(Message.ERROR, "Invalid protocol"));
				}
				if (sscUrl.indexOf(' ') != -1) {
					throw new FortifyException(new Message(Message.ERROR, "URL cannot have spaces"));
				}
			}
		}

		private void checkProxyUrlValue(String proxyUrl) throws FortifyException {
			if (StringUtils.isNotBlank(proxyUrl)) {
				String[] splits = proxyUrl.split(":");
				if (splits.length > 2) {
					throw new FortifyException(
							new Message(Message.ERROR, "Invalid proxy url.  Format is <hostname>[:<port>]"));
				}
				Pattern hostPattern = Pattern.compile("([\\w\\-]+\\.)*[\\w\\-]+");
				Matcher hostMatcher = hostPattern.matcher(splits[0]);
				if (!hostMatcher.matches()) {
					throw new FortifyException(new Message(Message.ERROR, "Invalid proxy host"));
				}
				if (splits.length == 2) {
					try {
						Integer.parseInt(splits[1]);
					} catch (NumberFormatException nfe) {
						throw new FortifyException(new Message(Message.ERROR, "Invalid proxy port"));
					}
				}
			}
		}

		private void checkProxyUsernameValue(String proxyUsername) throws FortifyException {
			// accept anything
		}

		private void checkProxyPasswordValue(String proxyPassword) throws FortifyException {
			// accept anything
		}

		private void checkProjectTemplateName(String projectTemplateName) throws FortifyException {
			if (!StringUtils.isEmpty(projectTemplateName)) {
				boolean valid = false;
				List<ProjectTemplateBean> projectTemplateList = getProjTemplateListList();
				if (projectTemplateList != null) {
					for (ProjectTemplateBean projectTemplateBean : projectTemplateList) {
						if (projectTemplateBean.getName().equals(projectTemplateName)) {
							valid = true;
						}
					}
					if (!valid) {
						throw new FortifyException(
								new Message(Message.ERROR, "Invalid Issue Template \"" + projectTemplateName + "\"."));
					}
				}
			}
		}

		private void checkCtrlUrlValue(String url) throws FortifyException {
			if (StringUtils.isBlank(url)) {
				throw new FortifyException(new Message(Message.ERROR, "Controller URL cannot be empty"));
			} else {
				if (StringUtils.startsWith(url, "http://") || StringUtils.startsWith(url, "https://")) {
					if (url.trim().equalsIgnoreCase("http://") || url.trim().equalsIgnoreCase("https://")) {
						throw new FortifyException(new Message(Message.ERROR, "URL host is required"));
					}
					if (!StringUtils.endsWith(url,"/cloud-ctrl")) {
						throw new FortifyException(new Message(Message.ERROR, "Invalid context"));
					}
				} else {
					throw new FortifyException(new Message(Message.ERROR, "Invalid protocol"));
				}
				if (url.indexOf(' ') != -1) {
					throw new FortifyException(new Message(Message.ERROR, "URL cannot have spaces"));
				}
			}
		}

		public void doRefreshProjects(StaplerRequest req, StaplerResponse rsp, @QueryParameter String value)
				throws Exception {
			try {
				// always retrieve data from SSC
				allProjects = getAllProjectsNoCache();
				// and then convert it to JSON
				StringBuilder buf = new StringBuilder();
				List<String> projects = new ArrayList<String>(allProjects.keySet());
				Collections.sort(projects, String.CASE_INSENSITIVE_ORDER);
				for (String prjName : projects) {
					if (buf.length() > 0) {
						buf.append(",");
					}
					buf.append("{ \"name\": \"" + prjName + "\" }\n");
				}
				buf.insert(0, "{ \"list\" : [\n");
				buf.append("]}");
				// send HTML data directly
				rsp.setContentType("text/html;charset=UTF-8");
				rsp.getWriter().print(buf.toString());
			} catch (Exception e) {
				e.printStackTrace();
				throw e;
			}
		}

		public void doRefreshVersions(StaplerRequest req, StaplerResponse rsp, @QueryParameter String value)
				throws Exception {
			try {
				// always retrieve data from SSC
				allProjects = getAllProjects();
				// and then convert it to JSON
				StringBuilder buf = new StringBuilder();
				for (Map.Entry<String, Map<String, Long>> prj : allProjects.entrySet()) {
					List<String> versions = new ArrayList<String>(prj.getValue().keySet());
					Collections.sort(versions, String.CASE_INSENSITIVE_ORDER);
					for (String prjVersion : versions) {
						if (buf.length() > 0) {
							buf.append(",");
						}
						buf.append("{ \"name\": \"" + prjVersion + "\", \"prj\": \"" + prj + "\" }\n");
					}
				}
				buf.insert(0, "{ \"list\" : [\n");
				buf.append("]}");
				// send HTML data directly
				rsp.setContentType("text/html;charset=UTF-8");
				rsp.getWriter().print(buf.toString());
			} catch (Exception e) {
				e.printStackTrace();
				throw e;
			}
		}

		public void doRefreshProjectTemplates(StaplerRequest req, StaplerResponse rsp, @QueryParameter String value)
				throws Exception {
			// backup original values
			String orig_url = this.url;
			boolean orig_useProxy = this.useProxy;
			String orig_proxyUrl = this.proxyUrl;
			Secret orig_proxyUsername = this.proxyUsername;
			Secret orig_proxyPassword = this.proxyPassword;
			Secret orig_token = this.token;

			String url = req.getParameter("url");
			boolean useProxy = "true".equals(req.getParameter("useProxy"));
			String proxyUrl = req.getParameter("proxyUrl");
			String proxyUsername = req.getParameter("proxyUsername");
			String proxyPassword = req.getParameter("proxyPassword");
			String token = req.getParameter("token");
			this.url = url != null ? url.trim() : "";
			this.useProxy = useProxy;
			if (useProxy) {
				this.proxyUrl = proxyUrl != null ? proxyUrl.trim() : "";
				this.proxyUsername = proxyUsername != null ? Secret.fromString(proxyUsername.trim()) : null;
				this.proxyPassword = proxyPassword != null ? Secret.fromString(proxyPassword) : null;
			} else {
				this.proxyUrl = "";
				this.proxyUsername = null;
				this.proxyPassword = null;
			}
			this.token = token != null ? Secret.fromString(token.trim()) : null;

			if (!doTestConnection(this.url, this.getToken(), null).kind.equals(FormValidation.Kind.OK)) {
				return; // don't get templates if server is unavailable
			}

			try {
				// always retrieve data from SSC
				projTemplateList = getProjTemplateListNoCache();
				// and then convert it to JSON
				StringBuilder buf = new StringBuilder();
				buf.append("{ \"list\" : [\n");
				for (int i = 0; i < projTemplateList.size(); i++) {
					ProjectTemplateBean b = projTemplateList.get(i);
					buf.append("{ \"name\": \"" + b.getName() + "\", \"id\": \"" + b.getId() + "\" }");
					if (i != projTemplateList.size() - 1) {
						buf.append(",\n");
					} else {
						buf.append("\n");
					}
				}
				buf.append("]}");
				// send HTML data directly
				rsp.setContentType("text/html;charset=UTF-8");
				rsp.getWriter().print(buf.toString());
			} catch (Exception e) {
				e.printStackTrace();
				throw e;
			} finally {
				this.url = orig_url;
				this.useProxy = orig_useProxy;
				this.proxyUrl = orig_proxyUrl;
				this.proxyUsername = orig_proxyUsername;
				this.proxyPassword = orig_proxyPassword;
				this.token = orig_token;
			}
		}

		public void doRefreshSensorPools(StaplerRequest req, StaplerResponse rsp, @QueryParameter String value)
				throws Exception {
			// backup original values
			String orig_url = this.url;
			boolean orig_useProxy = this.useProxy;
			String orig_proxyUrl = this.proxyUrl;
			Secret orig_proxyUsername = this.proxyUsername;
			Secret orig_proxyPassword = this.proxyPassword;
			Secret orig_token = this.token;

			String url = req.getParameter("url");
			boolean useProxy = "true".equals(req.getParameter("useProxy"));
			String proxyUrl = req.getParameter("proxyUrl");
			String proxyUsername = req.getParameter("proxyUsername");
			String proxyPassword = req.getParameter("proxyPassword");
			String token = req.getParameter("token");
			this.url = url != null ? url.trim() : "";
			this.useProxy = useProxy;
			if (useProxy) {
				this.proxyUrl = proxyUrl != null ? proxyUrl.trim() : "";
				this.proxyUsername = proxyUsername != null ? Secret.fromString(proxyUsername.trim()) : null;
				this.proxyPassword = proxyPassword != null ? Secret.fromString(proxyPassword) : null;
			} else {
				this.proxyUrl = "";
				this.proxyUsername = null;
				this.proxyPassword = null;
			}
			this.token = token != null ? Secret.fromString(token.trim()) : null;

			if (!doTestConnection(this.url, this.getToken(), null).kind.equals(FormValidation.Kind.OK)) {
				return; // don't get sensor pools if server is unavailable
			}

			try {
				// always retrieve data from SSC
				sensorPoolList = getSensorPoolListNoCache();
				// and then convert it to JSON
				StringBuilder buf = new StringBuilder();
				buf.append("{ \"list\" : [\n");
				for (int i = 0; i < sensorPoolList.size(); i++) {
					SensorPoolBean b = sensorPoolList.get(i);
					buf.append("{ \"name\": \"" + b.getName() + "\", \"uuid\": \"" + b.getUuid() + "\" }");
					if (i != sensorPoolList.size() - 1) {
						buf.append(",\n");
					} else {
						buf.append("\n");
					}
				}
				buf.append("]}");
				// send HTML data directly
				rsp.setContentType("text/html;charset=UTF-8");
				rsp.getWriter().print(buf.toString());
			} catch (Exception e) {
				e.printStackTrace();
				throw e;
			} finally {
				this.url = orig_url;
				this.useProxy = orig_useProxy;
				this.proxyUrl = orig_proxyUrl;
				this.proxyUsername = orig_proxyUsername;
				this.proxyPassword = orig_proxyPassword;
				this.token = orig_token;
			}
		}

		public void doCreateNewProject(final StaplerRequest req, StaplerResponse rsp, @QueryParameter String value) throws Exception {
			try {
				runWithFortifyClient(getToken(), new FortifyClient.Command<FortifyClient.NoReturn>() {
					@Override
					public NoReturn runWith(FortifyClient client) throws Exception {
						Writer w = new OutputStreamWriter(System.out, "UTF-8");
						client.createProject(req.getParameter("newprojName"), req.getParameter("newprojVersion"),
								req.getParameter("newprojTemplate"), Collections.<String, String>emptyMap(),
								new PrintWriter(w));
						return FortifyClient.NoReturn.INSTANCE;
					}
				});
			} catch (Exception e) {
				e.printStackTrace();
			}

			doRefreshProjects(req, rsp, value);
		}

		private boolean isSettingUpdated = false;

		@Override
		public boolean configure(StaplerRequest req, JSONObject o) throws FormException {
			// to persist global configuration information,
			// set that to properties and call save().
			try {
				url = o.getString("url").trim();
				checkUrlValue(url);
			} catch (JSONException e) {
				System.out.println("Cannot restore 'URL' property. Will use default (empty) values.");
				url = null;
			} catch (FortifyException e) {
				System.out.println(e.getMessage());
				url = null;
			}
			JSONObject useProxy = null;
			try {
				useProxy = o.getJSONObject("useProxy");
			} catch (JSONException e) {
			}
			if (useProxy == null || useProxy.isNullObject()) {
				this.useProxy = false;
			} else {
				this.useProxy = true;
				try {
					proxyUrl = useProxy.getString("proxyUrl").trim();
					checkProxyUrlValue(proxyUrl);
				} catch (JSONException e) {
					e.printStackTrace();
					System.out.println("Cannot restore 'proxyUrl' property.  Will use default (empty) values.");
					proxyUrl = null;
				} catch (FortifyException e) {
					System.out.println(e.getMessage());
					proxyUrl = null;
				}
				try {
					String usernameParam = useProxy.getString("proxyUsername").trim();
					checkProxyUsernameValue(usernameParam);
					proxyUsername = usernameParam.isEmpty() ? null : Secret.fromString(usernameParam);
				} catch (JSONException e) {
					System.out.println("Cannot restore 'proxyUsername' property.  Will use default (empty) values.");
					proxyUsername = null;
				} catch (FortifyException e) {
					System.out.println(e.getMessage());
					proxyUsername = null;
				}
				try {
					String pwdParam = useProxy.getString("proxyPassword").trim();
					checkProxyPasswordValue(pwdParam);
					proxyPassword = pwdParam.isEmpty() ? null : Secret.fromString(pwdParam);
				} catch (JSONException e) {
					System.out.println("Cannot restore 'proxyPassword' property.  Will use default (empty) values.");
					proxyPassword = null;
				} catch (FortifyException e) {
					System.out.println(e.getMessage());
					proxyPassword = null;
				}
			}
			try {
				String tokenParam = o.getString("token").trim();
				token = tokenParam.isEmpty() ? null : Secret.fromString(tokenParam);
			} catch (JSONException e) {
				System.out.println("Cannot restore 'Authentication Token' property. Will use default (empty) values.");
				token = null;
			}

			try {
				projectTemplate = o.getString("projectTemplate").trim();
			} catch (JSONException e) {
				System.out.println("Cannot restore 'Issue template' property. Will use default (empty) values.");
				projectTemplate = null;
			}

			try {
				String pageSizeString = o.getString("breakdownPageSize");
				if (pageSizeString != null && pageSizeString.trim().length() > 0) {
					breakdownPageSize = Integer.parseInt(pageSizeString.trim());
				} else {
					breakdownPageSize = DEFAULT_PAGE_SIZE;
				}
			} catch (NumberFormatException | JSONException e) {
				System.out.println("Cannot restore 'Issue breakdown page size' property. Will use default (" + DEFAULT_PAGE_SIZE + ") value.");
				breakdownPageSize = DEFAULT_PAGE_SIZE;
			}

			try {
				ctrlUrl = o.getString("ctrlUrl").trim();
				checkCtrlUrlValue(ctrlUrl);
			} catch (JSONException e) {
				System.out.println("Cannot restore 'CTRLURL' property. Will use default (empty) values.");
				ctrlUrl = null;
			} catch (FortifyException e) {
				System.out.println(e.getMessage());
				ctrlUrl = null;
			}

			try {
				String ctrlTokenParam = o.getString("ctrlToken").trim();
				ctrlToken = ctrlTokenParam.isEmpty() ? null : Secret.fromString(ctrlTokenParam);
			} catch (JSONException e) {
				System.out.println("Cannot restore 'Controller token' property. Will use default (empty) values.");
				ctrlToken = null;
			}

			save();
			isSettingUpdated = true;
			return super.configure(req, o);
		}

		public boolean isSettingUpdated() {
			try {
				return isSettingUpdated;
			} finally {
				isSettingUpdated = false;
			}
		}

		public ComboBoxModel doFillProjectNameItems() {
			Map<String, Map<String, Long>> allPrj = getAllProjects();
			return new ComboBoxModel(allPrj.keySet());
		}

		public ComboBoxModel getProjectNameItems() {
			return doFillProjectNameItems();
		}

		public ComboBoxModel doFillProjectVersionItems(@QueryParameter String projectName) {
			Map<String, Long> allPrjVersions = getAllProjects().get(projectName);
			if (null == allPrjVersions) {
				return new ComboBoxModel(Collections.<String>emptyList());
			}
			return new ComboBoxModel(allPrjVersions.keySet());
		}

		public ComboBoxModel getProjectVersionItems(@QueryParameter String projectName) {
			return doFillProjectVersionItems(projectName);
		}

		private Map<String, Map<String, Long>> getAllProjects() {
			if (allProjects.isEmpty()) {
				allProjects = getAllProjectsNoCache();
			}
			return allProjects;
		}

		private Map<String, Map<String, Long>> getAllProjectsNoCache() {
			if (canUploadToSsc()) {
				try {
					Map<String, Map<String, Long>> map = runWithFortifyClient(getToken(),
							new FortifyClient.Command<Map<String, Map<String, Long>>>() {
								@Override
								public Map<String, Map<String, Long>> runWith(FortifyClient client) throws Exception {
									return client.getProjectListEx();
								}
							});
					return map;
					// many strange thing can happen.... need to catch throwable
				} catch (Throwable e) {
					e.printStackTrace();
					return Collections.emptyMap();
				}
			} else {
				return Collections.emptyMap();
			}
		}

		/**
		 * Get Issue template list from SSC via WS <br/>
		 * Basically only for global.jelly pull down menu
		 * 
		 * @return A list of Issue template and ID
		 * @throws ApiException
		 */
		public ComboBoxModel doFillProjectTemplateItems() {
			if (projTemplateList.isEmpty()) {
				projTemplateList = getProjTemplateListNoCache();
			}

			List<String> names = new ArrayList<String>(projTemplateList.size());
			for (ProjectTemplateBean b : projTemplateList) {
				names.add(b.getName());
			}
			return new ComboBoxModel(names);
		}

		public ComboBoxModel getProjectTemplateItems() {
			return doFillProjectTemplateItems();
		}

		public List<ProjectTemplateBean> getProjTemplateListList() {
			if (projTemplateList.isEmpty()) {
				projTemplateList = getProjTemplateListNoCache();
			}
			return projTemplateList;
		}

		private List<ProjectTemplateBean> getProjTemplateListNoCache() {
			if (canUploadToSsc()) {
				try {
					Map<String, String> map = runWithFortifyClient(getToken(),
							new FortifyClient.Command<Map<String, String>>() {
								@Override
								public Map<String, String> runWith(FortifyClient client) throws Exception {
									return client.getProjectTemplateList();
								}
							});
					List<ProjectTemplateBean> list = new ArrayList<ProjectTemplateBean>(map.size());
					for (Map.Entry<String, String> entry : map.entrySet()) {
						ProjectTemplateBean proj = new ProjectTemplateBean(entry.getKey(), entry.getValue());
						list.add(proj);
					}
					Collections.sort(list);
					return list;
				} catch (Throwable e) {
					e.printStackTrace();
				}
			}
			return Collections.emptyList();
		}

		public ListBoxModel doFillSensorPoolNameItems() {
			sensorPoolList = getSensorPoolListNoCache();

			List<ListBoxModel.Option> optionList = new ArrayList<>();

			for (SensorPoolBean sensorPoolBean : sensorPoolList) {
				ListBoxModel.Option option = new ListBoxModel.Option(sensorPoolBean.getName(), sensorPoolBean.getUuid());
				optionList.add(option);
			}

			return new ListBoxModel(optionList);
		}

		public List<SensorPoolBean> getSensorPoolList() {
			if (sensorPoolList.isEmpty()) {
				sensorPoolList = getSensorPoolListNoCache();
			}
			return sensorPoolList;
		}

		private List<SensorPoolBean> getSensorPoolListNoCache() {
			try {
				Map<String, String> map = runWithFortifyClient(getToken(),
						new FortifyClient.Command<Map<String, String>>() {
							@Override
							public Map<String, String> runWith(FortifyClient client) throws Exception {
								return client.getCloudScanPoolList();
							}
						});
				List<SensorPoolBean> list = new ArrayList<SensorPoolBean>(map.size());
				for (Map.Entry<String, String> entry : map.entrySet()) {
					SensorPoolBean proj = new SensorPoolBean(entry.getKey(), entry.getValue());
					list.add(proj);
				}
				Collections.sort(list);
				return list;
			} catch (Throwable e) {
				e.printStackTrace();
			}

			return Collections.emptyList();
		}

		public ListBoxModel doFillTranslationApplicationTypeItems() {
			ListBoxModel options = new ListBoxModel(5);
			options.add("Java", "java");
			options.add(".NET", "dotnet");
			options.add("Maven 3", "maven3");
			options.add("Gradle", "gradle");
			options.add("Other", "other");
			return options;
		}

		public ListBoxModel doFillTranslationJavaVersionItems() {
			ListBoxModel options = new ListBoxModel();
			options.add("1.5", "1.5");
			options.add("1.6", "1.6");
			options.add("1.7", "1.7");
			options.add("1.8", "1.8");
			options.add("1.9", "1.9");
			return options;
		}
	}

	public static class UploadSSCBlock {
		private String projectName;
		private String projectVersion;
		private String filterSet;
		private String searchCondition;
		private String pollingInterval;

		@DataBoundConstructor
		public UploadSSCBlock(String projectName, String projectVersion) {
			this.projectName = projectName != null ? projectName.trim() : "";
			this.projectVersion = projectName != null ? projectVersion.trim() : "";
		}

		@Deprecated
		public UploadSSCBlock(String projectName, String projectVersion, String filterSet, String searchCondition, String pollingInterval) {
			this.projectName = projectName != null ? projectName.trim() : "";
			this.projectVersion = projectName != null ? projectVersion.trim() : "";
			this.filterSet = filterSet != null ? filterSet.trim() : "";
			this.searchCondition = searchCondition != null ? searchCondition.trim() : "";
			this.pollingInterval = pollingInterval != null ? pollingInterval.trim() : "";
		}

		public String getProjectName() {
			return projectName;
		}

		public String getProjectVersion() {
			return projectVersion;
		}

		public String getFilterSet() {
			return filterSet;
		}
		@DataBoundSetter
		public void setFilterSet(String filterSet) { this.filterSet = filterSet; }

		public String getSearchCondition() {
			return searchCondition;
		}
		@DataBoundSetter
		public void setSearchCondition(String searchCondition) { this.searchCondition = searchCondition; }

		public String getPollingInterval() {
			return pollingInterval;
		}
		@DataBoundSetter
		public void setPollingInterval(String pollingInterval) { this.pollingInterval = pollingInterval; }
	}

	@Deprecated
	public static class RunTranslationBlock {
		private TranslationTypeBlock translationType;
		private boolean debug;
		private boolean verbose;
		private String logFile;

		@DataBoundConstructor
		public RunTranslationBlock(TranslationTypeBlock translationType, boolean translationDebug,
				boolean translationVerbose, String translationLogFile) {
			this.translationType = translationType;
			this.debug = translationDebug;
			this.verbose = translationVerbose;
			this.logFile = translationLogFile != null ? translationLogFile.trim() : "";
		}

		public boolean isBasicTranslationType() {
			return translationType instanceof BasicTranslationBlock;
		}

		public boolean isAdvancedTranslationType() {
			return translationType instanceof AdvancedTranslationBlock;
		}

		public boolean isBasicJavaTranslationType() {
			return isBasicTranslationType() && ((BasicTranslationBlock) translationType)
					.getTranslationApplicationTypeBlock() instanceof BasicJavaTranslationAppTypeBlock;
		}

		public boolean isBasicDotNetTranslationType() {
			return isBasicTranslationType() && ((BasicTranslationBlock) translationType)
					.getTranslationApplicationTypeBlock() instanceof BasicDotNetTranslationAppTypeBlock;
		}

		public boolean isBasicMaven3TranslationType() {
			return isBasicTranslationType() && ((BasicTranslationBlock) translationType)
					.getTranslationApplicationTypeBlock() instanceof BasicMaven3TranslationAppTypeBlock;
		}

		public boolean isBasicGradleTranslationType() {
			return isBasicTranslationType() && ((BasicTranslationBlock) translationType)
					.getTranslationApplicationTypeBlock() instanceof BasicGradleTranslationAppTypeBlock;
		}

		public boolean isBasicOtherTranslationType() {
			return isBasicTranslationType() && ((BasicTranslationBlock) translationType)
					.getTranslationApplicationTypeBlock() instanceof BasicOtherTranslationAppTypeBlock;
		}

		public boolean isBasicDotNetProjectSolutionScanType() {
			return isBasicDotNetTranslationType()
					&& getBasicDotNetTranslationAppTypeBlock().isProjectSolutionScanType();
		}

		public boolean isBasicDotNetSourceCodeScanType() {
			return isBasicDotNetTranslationType() && getBasicDotNetTranslationAppTypeBlock().isSourceCodeScanType();
		}

		public boolean isBasicDotNetDevenvBuildType() {
			return isBasicDotNetProjectSolutionScanType()
					&& getBasicDotNetTranslationAppTypeBlock().isDevenvBuildType();
		}

		public boolean isBasicDotNetMSBuildBuildType() {
			return isBasicDotNetProjectSolutionScanType()
					&& getBasicDotNetTranslationAppTypeBlock().isMSBuildBuildType();
		}

		private BasicJavaTranslationAppTypeBlock getBasicJavaTranslationAppTypeBlock() {
			return isBasicJavaTranslationType()
					? (BasicJavaTranslationAppTypeBlock) ((BasicTranslationBlock) translationType)
							.getTranslationApplicationTypeBlock()
					: null;
		}

		private BasicDotNetTranslationAppTypeBlock getBasicDotNetTranslationAppTypeBlock() {
			return isBasicDotNetTranslationType()
					? (BasicDotNetTranslationAppTypeBlock) ((BasicTranslationBlock) translationType)
							.getTranslationApplicationTypeBlock()
					: null;
		}

		private BasicMaven3TranslationAppTypeBlock getBasicMaven3TranslationAppTypeBlock() {
			return isBasicMaven3TranslationType()
					? (BasicMaven3TranslationAppTypeBlock) ((BasicTranslationBlock) translationType)
							.getTranslationApplicationTypeBlock()
					: null;
		}

		private BasicGradleTranslationAppTypeBlock getBasicGradleTranslationAppTypeBlock() {
			return isBasicGradleTranslationType()
					? (BasicGradleTranslationAppTypeBlock) ((BasicTranslationBlock) translationType)
							.getTranslationApplicationTypeBlock()
					: null;
		}

		private BasicOtherTranslationAppTypeBlock getBasicOtherTranslationAppTypeBlock() {
			return isBasicOtherTranslationType()
					? (BasicOtherTranslationAppTypeBlock) ((BasicTranslationBlock) translationType)
							.getTranslationApplicationTypeBlock()
					: null;
		}

		public String getTranslationType() {
			return isBasicTranslationType() ? "translationBasic" : "translationAdvanced";
		}

		public String getTranslationOptions() {
			return isAdvancedTranslationType() ? ((AdvancedTranslationBlock) translationType).getTranslationOptions()
					: "";
		}

		public String getTranslationExcludeList() {
			return isBasicTranslationType() ? ((BasicTranslationBlock) translationType).getTranslationExcludeList()
					: "";
		}

		public String getTranslationJavaVersion() {
			return isBasicJavaTranslationType() ? getBasicJavaTranslationAppTypeBlock().getTranslationJavaVersion()
					: "";
		}

		public String getTranslationClasspath() {
			return isBasicJavaTranslationType() ? getBasicJavaTranslationAppTypeBlock().getTranslationClasspath() : "";
		}

		public String getTranslationSourceFiles() {
			return isBasicJavaTranslationType() ? getBasicJavaTranslationAppTypeBlock().getTranslationSourceFiles()
					: "";
		}

		public String getTranslationAddOptions() {
			return isBasicJavaTranslationType() ? getBasicJavaTranslationAppTypeBlock().getTranslationAddOptions() : "";
		}

		public String getDotNetDevenvProjects() {
			return isBasicDotNetTranslationType() ? getBasicDotNetTranslationAppTypeBlock().getDevenvProjects() : "";
		}

		public String getDotNetDevenvAddOptions() {
			return isBasicDotNetTranslationType() ? getBasicDotNetTranslationAppTypeBlock().getDevenvAddOptions() : "";
		}

		public String getDotNetMSBuildProjects() {
			return isBasicDotNetTranslationType() ? getBasicDotNetTranslationAppTypeBlock().getMSBuildProjects() : "";
		}

		public String getDotNetMSBuildAddOptions() {
			return isBasicDotNetTranslationType() ? getBasicDotNetTranslationAppTypeBlock().getMSBuildAddOptions() : "";
		}

		public String getDotNetSourceCodeFrameworkVersion() {
			return isBasicDotNetTranslationType()
					? getBasicDotNetTranslationAppTypeBlock().getSourceCodeFrameworkVersion()
					: "";
		}

		public String getDotNetSourceCodeLibdirs() {
			return isBasicDotNetTranslationType() ? getBasicDotNetTranslationAppTypeBlock().getSourceCodeLibdirs() : "";
		}

		public String getDotNetSourceCodeAddOptions() {
			return isBasicDotNetTranslationType() ? getBasicDotNetTranslationAppTypeBlock().getSourceCodeAddOptions()
					: "";
		}

		public String getDotNetSourceCodeSrcFiles() {
			return isBasicDotNetTranslationType() ? getBasicDotNetTranslationAppTypeBlock().getSourceCodeSrcFiles()
					: "";
		}

		public String getMaven3Options() {
			return isBasicMaven3TranslationType() ? getBasicMaven3TranslationAppTypeBlock().getOptions() : "";
		}

		public boolean getGradleUseWrapper() {
			return isBasicGradleTranslationType() && getBasicGradleTranslationAppTypeBlock().getUseWrapper();
		}

		public String getGradleTasks() {
			return isBasicGradleTranslationType() ? getBasicGradleTranslationAppTypeBlock().getTasks() : "";
		}

		public String getGradleOptions() {
			return isBasicGradleTranslationType() ? getBasicGradleTranslationAppTypeBlock().getOptions() : "";
		}

		public String getOtherOptions() {
			return isBasicOtherTranslationType() ? getBasicOtherTranslationAppTypeBlock().getOptions() : "";
		}

		public String getOtherIncludesList() {
			return isBasicOtherTranslationType() ? getBasicOtherTranslationAppTypeBlock().getIncludesList() : "";
		}

		public boolean getTranslationDebug() {
			return debug;
		}

		public boolean getTranslationVerbose() {
			return verbose;
		}

		public String getTranslationLogFile() {
			return logFile;
		}
	}

	@Deprecated
	public interface TranslationTypeBlock {
	}

	@Deprecated
	public static class BasicTranslationBlock implements TranslationTypeBlock {
		private BasicTranslationAppTypeBlock appTypeBlock;
		private String excludeList;

		@DataBoundConstructor
		public BasicTranslationBlock(BasicTranslationAppTypeBlock translationAppType, String translationExcludeList) {
			this.appTypeBlock = translationAppType;
			this.excludeList = translationExcludeList != null ? translationExcludeList.trim() : "";
		}

		public BasicTranslationAppTypeBlock getTranslationApplicationTypeBlock() {
			return appTypeBlock;
		}

		public String getTranslationExcludeList() {
			return excludeList;
		}
	}

	@Deprecated
	public static class AdvancedTranslationBlock implements TranslationTypeBlock {
		private String translationOptions;

		@DataBoundConstructor
		public AdvancedTranslationBlock(String translationOptions) {
			this.translationOptions = translationOptions != null ? translationOptions.trim() : "";
		}

		public String getTranslationOptions() {
			return translationOptions;
		}
	}

	@Deprecated
	public interface BasicTranslationAppTypeBlock {
	}

	@Deprecated
	public static class BasicJavaTranslationAppTypeBlock implements BasicTranslationAppTypeBlock {
		private String javaVersion;
		private String classpath;
		private String sourceFiles;
		private String additionalOptions;

		@DataBoundConstructor
		public BasicJavaTranslationAppTypeBlock(String translationJavaVersion, String translationJavaClasspath,
				String translationJavaSourceFiles, String translationJavaAddOptions) {
			this.javaVersion = translationJavaVersion != null ? translationJavaVersion.trim() : "";
			this.classpath = translationJavaClasspath != null ? translationJavaClasspath.trim() : "";
			this.sourceFiles = translationJavaSourceFiles != null ? translationJavaSourceFiles.trim() : "";
			this.additionalOptions = translationJavaAddOptions != null ? translationJavaAddOptions.trim() : "";
		}

		public String getTranslationJavaVersion() {
			return javaVersion;
		}

		public String getTranslationClasspath() {
			return classpath;
		}

		public String getTranslationSourceFiles() {
			return sourceFiles;
		}

		public String getTranslationAddOptions() {
			return additionalOptions;
		}
	}

	@Deprecated
	public static class BasicDotNetTranslationAppTypeBlock implements BasicTranslationAppTypeBlock {
		private BasicDotNetScanTypeBlock scanType;

		@DataBoundConstructor
		public BasicDotNetTranslationAppTypeBlock(BasicDotNetScanTypeBlock dotNetScanType) {
			scanType = dotNetScanType;
		}

		public boolean isProjectSolutionScanType() {
			return scanType != null && scanType instanceof BasicDotNetProjectSolutionScanTypeBlock;
		}

		public boolean isSourceCodeScanType() {
			return scanType != null && scanType instanceof BasicDotNetSourceCodeScanTypeBlock;
		}

		public BasicDotNetScanTypeBlock getScanTypeBlock() {
			return scanType;
		}

		public boolean isDevenvBuildType() {
			return isProjectSolutionScanType()
					&& ((BasicDotNetProjectSolutionScanTypeBlock) scanType).isDevenvBuildType();
		}

		public boolean isMSBuildBuildType() {
			return isProjectSolutionScanType()
					&& ((BasicDotNetProjectSolutionScanTypeBlock) scanType).isMSBuildBuildType();
		}

		public String getDevenvProjects() {
			return isProjectSolutionScanType()
					? ((BasicDotNetProjectSolutionScanTypeBlock) scanType).getDevenvProjects()
					: "";
		}

		public String getDevenvAddOptions() {
			return isProjectSolutionScanType()
					? ((BasicDotNetProjectSolutionScanTypeBlock) scanType).getDevenvAddOptions()
					: "";
		}

		public String getMSBuildProjects() {
			return isProjectSolutionScanType()
					? ((BasicDotNetProjectSolutionScanTypeBlock) scanType).getMSBuildProjects()
					: "";
		}

		public String getMSBuildAddOptions() {
			return isProjectSolutionScanType()
					? ((BasicDotNetProjectSolutionScanTypeBlock) scanType).getMSBuildAddOptions()
					: "";
		}

		public String getSourceCodeFrameworkVersion() {
			return isSourceCodeScanType() ? ((BasicDotNetSourceCodeScanTypeBlock) scanType).getDotNetVersion() : "";
		}

		public String getSourceCodeLibdirs() {
			return isSourceCodeScanType() ? ((BasicDotNetSourceCodeScanTypeBlock) scanType).getLibdirs() : "";
		}

		public String getSourceCodeAddOptions() {
			return isSourceCodeScanType() ? ((BasicDotNetSourceCodeScanTypeBlock) scanType).getAddOptions() : "";
		}

		public String getSourceCodeSrcFiles() {
			return isSourceCodeScanType() ? ((BasicDotNetSourceCodeScanTypeBlock) scanType).getDotNetSrcFiles() : "";
		}
	}

	@Deprecated
	public interface BasicDotNetScanTypeBlock {
	}

	@Deprecated
	public static class BasicDotNetProjectSolutionScanTypeBlock implements BasicDotNetScanTypeBlock {
		private BasicDotNetBuildTypeBlock buildType;

		@DataBoundConstructor
		public BasicDotNetProjectSolutionScanTypeBlock(BasicDotNetBuildTypeBlock dotNetBuildType) {
			buildType = dotNetBuildType;
		}

		public boolean isDevenvBuildType() {
			return buildType != null && buildType instanceof BasicDotNetDevenvBuildTypeBlock;
		}

		public boolean isMSBuildBuildType() {
			return buildType != null && buildType instanceof BasicDotNetMSBuildBuildTypeBlock;
		}

		public String getDevenvProjects() {
			return isDevenvBuildType() ? ((BasicDotNetDevenvBuildTypeBlock) buildType).getProjects() : "";
		}

		public String getDevenvAddOptions() {
			return isDevenvBuildType() ? ((BasicDotNetDevenvBuildTypeBlock) buildType).getAddOptions() : "";
		}

		public String getMSBuildProjects() {
			return isMSBuildBuildType() ? ((BasicDotNetMSBuildBuildTypeBlock) buildType).getProjects() : "";
		}

		public String getMSBuildAddOptions() {
			return isMSBuildBuildType() ? ((BasicDotNetMSBuildBuildTypeBlock) buildType).getAddOptions() : "";
		}
	}

	@Deprecated
	public static class BasicDotNetSourceCodeScanTypeBlock implements BasicDotNetScanTypeBlock {
		private String dotNetVersion;
		private String libdirs;
		private String addOptions;
		private String dotNetSrcFiles;

		@DataBoundConstructor
		public BasicDotNetSourceCodeScanTypeBlock(String dotNetSourceCodeFrameworkVersion,
				String dotNetSourceCodeLibdirs, String dotNetSourceCodeAddOptions, String dotNetSourceCodeSrcFiles) {
			dotNetVersion = dotNetSourceCodeFrameworkVersion;
			libdirs = dotNetSourceCodeLibdirs;
			addOptions = dotNetSourceCodeAddOptions;
			dotNetSrcFiles = dotNetSourceCodeSrcFiles;
		}

		public String getDotNetVersion() {
			return dotNetVersion;
		}

		public String getLibdirs() {
			return libdirs;
		}

		public String getAddOptions() {
			return addOptions;
		}

		public String getDotNetSrcFiles() {
			return dotNetSrcFiles;
		}
	}

	@Deprecated
	public interface BasicDotNetBuildTypeBlock {
	}

	@Deprecated
	public static class BasicDotNetDevenvBuildTypeBlock implements BasicDotNetBuildTypeBlock {
		private String projects;
		private String addOptions;

		@DataBoundConstructor
		public BasicDotNetDevenvBuildTypeBlock(String dotNetDevenvProjects, String dotNetDevenvAddOptions) {
			projects = dotNetDevenvProjects;
			addOptions = dotNetDevenvAddOptions;
		}

		public String getProjects() {
			return projects;
		}

		public String getAddOptions() {
			return addOptions;
		}
	}

	@Deprecated
	public static class BasicDotNetMSBuildBuildTypeBlock implements BasicDotNetBuildTypeBlock {
		private String projects;
		private String addOptions;

		@DataBoundConstructor
		public BasicDotNetMSBuildBuildTypeBlock(String dotNetMSBuildProjects, String dotNetMSBuildAddOptions) {
			projects = dotNetMSBuildProjects;
			addOptions = dotNetMSBuildAddOptions;
		}

		public String getProjects() {
			return projects;
		}

		public String getAddOptions() {
			return addOptions;
		}
	}

	@Deprecated
	public static class BasicMaven3TranslationAppTypeBlock implements BasicTranslationAppTypeBlock {
		private String options;

		@DataBoundConstructor
		public BasicMaven3TranslationAppTypeBlock(String maven3Options) {
			options = maven3Options;
		}

		public String getOptions() {
			return options;
		}
	}

	@Deprecated
	public static class BasicGradleTranslationAppTypeBlock implements BasicTranslationAppTypeBlock {
		private boolean useWrapper;
		private String tasks;
		private String options;

		@DataBoundConstructor
		public BasicGradleTranslationAppTypeBlock(boolean gradleUseWrapper, String gradleTasks, String gradleOptions) {
			useWrapper = gradleUseWrapper;
			tasks = gradleTasks;
			options = gradleOptions;
		}

		public boolean getUseWrapper() {
			return useWrapper;
		}

		public String getTasks() {
			return tasks;
		}

		public String getOptions() {
			return options;
		}
	}

	@Deprecated
	public static class BasicOtherTranslationAppTypeBlock implements BasicTranslationAppTypeBlock {
		private String options;
		private String includesList;

		@DataBoundConstructor
		public BasicOtherTranslationAppTypeBlock(String otherOptions, String otherIncludesList) {
			options = otherOptions;
			includesList = otherIncludesList;
		}

		public String getOptions() {
			return options;
		}

		public String getIncludesList() {
			return includesList;
		}
	}

	public static class RunScanBlock {
		private String customRulepacks;
		private String additionalOptions;
		private boolean debug;
		private boolean verbose;
		private String logFile;

		@DataBoundConstructor
		public RunScanBlock() {}

		@Deprecated
		public RunScanBlock(String scanCustomRulepacks, String scanAddOptions, boolean scanDebug, boolean scanVerbose,
				String scanLogFile) {
			this.customRulepacks = scanCustomRulepacks != null ? scanCustomRulepacks.trim() : "";
			this.additionalOptions = scanAddOptions != null ? scanAddOptions.trim() : "";
			this.debug = scanDebug;
			this.verbose = scanVerbose;
			this.logFile = scanLogFile != null ? scanLogFile.trim() : "";
		}

		public String getScanCustomRulepacks() {
			return customRulepacks;
		}
		@DataBoundSetter
		public void setScanCustomRulepacks(String scanCustomRulepacks) {
			this.customRulepacks = scanCustomRulepacks;
		}

		public String getScanAddOptions() {
			return additionalOptions;
		}
		@DataBoundSetter
		public void setScanAddOptions(String scanAddOptions) {
			this.additionalOptions = scanAddOptions;
		}

		public boolean getScanDebug() {
			return debug;
		}
		@DataBoundSetter
		public void setScanDebug(boolean scanDebug) { this.debug = scanDebug; }

		public boolean getScanVerbose() {
			return verbose;
		}
		@DataBoundSetter
		public void setScanVerbose(boolean scanVerbose) { this.verbose = scanVerbose; }

		public String getScanLogFile() {
			return logFile;
		}
		@DataBoundSetter
		public void setScanLogFile(String scanLogFile) { this.logFile = scanLogFile; }
	}

	public static class UpdateContentBlock {
		private String updateServerUrl;
		private UseProxyBlock useProxy;

		@DataBoundConstructor
		public UpdateContentBlock() {}

		@Deprecated
		public UpdateContentBlock(String updateServerUrl, UseProxyBlock updateUseProxy) {
			this.updateServerUrl = updateServerUrl != null ? updateServerUrl.trim() : "";
			this.useProxy = updateUseProxy;
		}

		public String getUpdateServerUrl() {
			return updateServerUrl;
		}
		@DataBoundSetter
		public void setUpdateServerUrl(String updateServerUrl) { this.updateServerUrl = updateServerUrl; }

		@Deprecated
		public boolean getUpdateUseProxy() {
			return useProxy != null;
		}

		@Deprecated
		public String getUpdateProxyUrl() {
			return useProxy == null ? "" : useProxy.getProxyUrl();
		}

		@Deprecated
		public String getUpdateProxyUsername() {
			return useProxy == null ? "" : useProxy.getProxyUsername();
		}

		@Deprecated
		public String getUpdateProxyPassword() {
			return useProxy == null ? "" : useProxy.getProxyPassword();
		}
	}

	@Deprecated
	public static class UseProxyBlock {
		private String proxyUrl;
		private String proxyUsername;
		private String proxyPassword;

		@DataBoundConstructor
		public UseProxyBlock(String updateProxyUrl, String updateProxyUsername, String updateProxyPassword) {
			this.proxyUrl = updateProxyUrl != null ? updateProxyUrl.trim() : "";
			this.proxyUsername = updateProxyUsername != null ? updateProxyUsername.trim() : "";
			this.proxyPassword = updateProxyPassword != null ? updateProxyPassword.trim() : "";
		}

		public String getProxyUrl() {
			return proxyUrl;
		}

		public String getProxyUsername() {
			return proxyUsername;
		}

		public String getProxyPassword() {
			return proxyPassword;
		}
	}

	public static class AnalysisRunType {
		private String value;
		// remote translation and scan
		private RemoteAnalysisProjectType remoteAnalysisProjectType;
		private RemoteOptionalConfigBlock remoteOptionalConfig;
		private String transArgs;

		// local translation
		private ProjectScanType projectScanType;
		private UpdateContentBlock updateContent;
		private boolean runSCAClean;
		private String buildId;
		private String scanFile;
		private String maxHeap;
		private String addJVMOptions;
		private String translationExcludeList;
		private boolean translationDebug;
		private boolean translationVerbose;
		private String translationLogFile;

		// mixed - remote scan
		private RunRemoteScanBlock runRemoteScan;
		// local scan
		private RunScanBlock runScan;

		// upload to ssc
		private UploadSSCBlock uploadSSC;

		@DataBoundConstructor
		public AnalysisRunType(String value) {
			this.value = value;
		}

		public RemoteAnalysisProjectType getRemoteAnalysisProjectType() { return remoteAnalysisProjectType; }
		@DataBoundSetter
		public void setRemoteAnalysisProjectType(RemoteAnalysisProjectType remoteAnalysisProjectType) {
			this.remoteAnalysisProjectType = remoteAnalysisProjectType;
		}

		public RemoteOptionalConfigBlock getRemoteOptionalConfig() { return remoteOptionalConfig; }
		@DataBoundSetter
		public void setRemoteOptionalConfig(RemoteOptionalConfigBlock remoteOptionalConfig) { this.remoteOptionalConfig = remoteOptionalConfig; }

		public String getTransArgs() { return transArgs; }
		@DataBoundSetter
		public void setTransArgs(String transArgs) { this.transArgs = transArgs; }

		public ProjectScanType getProjectScanType() {
			return projectScanType;
		}
		@DataBoundSetter
		public void setProjectScanType(ProjectScanType projectScanType) { this.projectScanType = projectScanType; }

		public UpdateContentBlock getUpdateContent() { return updateContent; }
		@DataBoundSetter
		public void setUpdateContent(UpdateContentBlock updateContent) { this.updateContent = updateContent; }

		public boolean isRunSCAClean() { return runSCAClean; }
		@DataBoundSetter
		public void setRunSCAClean(boolean runSCAClean) { this.runSCAClean = runSCAClean; }

		public String getBuildId() { return buildId; }
		@DataBoundSetter
		public void setBuildId(String buildId) { this.buildId = buildId; }

		public String getScanFile() { return scanFile; }
		@DataBoundSetter
		public void setScanFile(String scanFile) { this.scanFile = scanFile; }

		public String getMaxHeap() { return maxHeap; }
		@DataBoundSetter
		public void setMaxHeap(String maxHeap) { this.maxHeap = maxHeap; }

		public String getAddJVMOptions() { return addJVMOptions; }
		@DataBoundSetter
		public void setAddJVMOptions(String addJVMOptions) { this.addJVMOptions = addJVMOptions; }

		public String getTranslationExcludeList() { return translationExcludeList; }
		@DataBoundSetter
		public void setTranslationExcludeList(String translationExcludeList) { this.translationExcludeList = translationExcludeList; }

		public boolean isTranslationDebug() { return translationDebug; }
		@DataBoundSetter
		public void setTranslationDebug(boolean translationDebug) { this.translationDebug = translationDebug; }

		public boolean isTranslationVerbose() {
			return translationVerbose;
		}
		@DataBoundSetter
		public void setTranslationVerbose(boolean translationVerbose) { this.translationVerbose = translationVerbose; }

		public String getTranslationLogFile() {
			return translationLogFile;
		}
		@DataBoundSetter
		public void setTranslationLogFile(String translationLogFile) { this.translationLogFile = translationLogFile; }

		public RunScanBlock getRunScan() { return runScan; }
		@DataBoundSetter
		public void setRunScan(RunScanBlock runScan) { this.runScan = runScan; }

		public RunRemoteScanBlock getRunRemoteScan() { return runRemoteScan; }
		@DataBoundSetter
		public void setRunRemoteScan(RunRemoteScanBlock runRemoteScan) { this.runRemoteScan = runRemoteScan; }

		public UploadSSCBlock getUploadSSC() { return uploadSSC; }
		@DataBoundSetter
		public void setUploadSSC(UploadSSCBlock uploadSSC) { this.uploadSSC = uploadSSC; }

	}

	public static class RunRemoteScanBlock {
		private MbsScanBlock mbsType;
		private RemoteOptionalConfigBlock remoteOptionalConfig;

		@DataBoundConstructor
		public RunRemoteScanBlock() {}

		public MbsScanBlock getMbsType() { return mbsType; }
		@DataBoundSetter
		public void setMbsType(MbsScanBlock mbsType) { this.mbsType = mbsType; }

		public RemoteOptionalConfigBlock getRemoteOptionalConfig() { return remoteOptionalConfig; }
		@DataBoundSetter
		public void setRemoteOptionalConfig(RemoteOptionalConfigBlock remoteOptionalConfig) { this.remoteOptionalConfig = remoteOptionalConfig; }

	}

	public static class MbsScanBlock {
		private String value;
		private String fileName;

		@DataBoundConstructor
		public MbsScanBlock(String value, String fileName) {
			this.value = value;
			this.fileName = fileName;
		}

		public String getValue() {
			return value;
		}
		public String getFileName() {
			return fileName;
		}

	}

	public static class RemoteOptionalConfigBlock {
		private String sensorPoolName;
		private String emailAddr;
		private String scaScanOptions;
		private String rulepacks;
		private String filterFile;

		@DataBoundConstructor
		public RemoteOptionalConfigBlock() {}

		public String getSensorPoolName() { return  sensorPoolName; }
		@DataBoundSetter
		public void setSensorPoolName(String sensorPoolName) { this.sensorPoolName = sensorPoolName; }

		public String getEmailAddr() { return emailAddr; }
		@DataBoundSetter
		public void setEmailAddr(String emailAddr) { this.emailAddr = emailAddr; }

		public String getScaScanOptions() { return scaScanOptions; }
		@DataBoundSetter
		public void setScaScanOptions(String scaScanOptions) { this.scaScanOptions = scaScanOptions; }

		public String getRulepacks() { return rulepacks; }
		@DataBoundSetter
		public void setRulepacks(String rulepacks) { this.rulepacks = rulepacks;
		}

		public String getFilterFile() { return filterFile; }
		@DataBoundSetter
		public void setFilterFile(String filterFile) { this.filterFile = filterFile; }

	}

}

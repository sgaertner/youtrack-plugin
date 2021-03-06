package org.jenkinsci.plugins.youtrack;

import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.*;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.youtrack.youtrackapi.BuildBundle;
import org.jenkinsci.plugins.youtrack.youtrackapi.Issue;
import org.jenkinsci.plugins.youtrack.youtrackapi.User;
import org.jenkinsci.plugins.youtrack.youtrackapi.YouTrackServer;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import java.io.IOException;
import java.util.List;

/**
 * Updates build bundle.
 */
public class YouTrackBuildUpdater extends Recorder {

    /**
     * This was the name, where there was an implicit ${BUILD_NUMBER} (name) format.
     * @deprecated {@link #buildName} should be used instead.
     */
    private String name;
    /**
     * Name of build to create and use for setting Fixed in build.
     */
    private String buildName;
    private String bundleName;
    private boolean markFixedIfUnstable;
    private boolean onlyAddIfHasFixedIssues;
    private boolean runSilently;

    @DataBoundConstructor
    public YouTrackBuildUpdater(String name, String bundleName, String buildName, boolean markFixedIfUnstable, boolean onlyAddIfHasFixedIssues, boolean runSilently) {
        this.name = name;
        this.bundleName = bundleName;


        this.buildName = buildName;
        this.markFixedIfUnstable = markFixedIfUnstable;
        this.onlyAddIfHasFixedIssues = onlyAddIfHasFixedIssues;
        this.runSilently = runSilently;
    }



    @Deprecated
    public String getName() {
        return name;
    }

    @Deprecated
    public void setName(String name) {
        this.name = name;
    }

    public String getBuildName() {
        if (name != null && buildName == null) {
            this.buildName = "${BUILD_NUMBER} ("+name+")";
        }
        return buildName;
    }

    public void setBuildName(String buildName) {
        this.buildName = buildName;
    }

    public String getBundleName() {
        return bundleName;
    }

    public void setBundleName(String bundleName) {
        this.bundleName = bundleName;
    }

    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    public boolean isMarkFixedIfUnstable() {
        return markFixedIfUnstable;
    }

    public void setMarkFixedIfUnstable(boolean markFixedIfUnstable) {
        this.markFixedIfUnstable = markFixedIfUnstable;
    }

    public boolean isOnlyAddIfHasFixedIssues() {
        return onlyAddIfHasFixedIssues;
    }

    public void setOnlyAddIfHasFixedIssues(boolean onlyAddIfHasFixedIssues) {
        this.onlyAddIfHasFixedIssues = onlyAddIfHasFixedIssues;
    }

    public boolean isRunSilently() {
        return runSilently;
    }

    public void setRunSilently(boolean runSilently) {
        this.runSilently = runSilently;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {

        YouTrackSite youTrackSite = YouTrackSite.get(build.getProject());
        if (youTrackSite == null || !youTrackSite.isPluginEnabled()) {
            return true;
        }


        YouTrackSaveFixedIssues action = build.getAction(YouTrackSaveFixedIssues.class);

        YouTrackCommandAction youTrackCommandAction = build.getAction(YouTrackCommandAction.class);
        if(youTrackCommandAction == null) {
            youTrackCommandAction = new YouTrackCommandAction(build);
            build.addAction(youTrackCommandAction);
        }

        //Return early if there is no build to be added
        if(onlyAddIfHasFixedIssues) {
            if(action == null) {
                return true;
            }
            if(action.getIssueIds().isEmpty()) {
                return true;
            }
        }

        YouTrackServer youTrackServer = new YouTrackServer(youTrackSite.getUrl());
        User user = youTrackServer.login(youTrackSite.getUsername(), youTrackSite.getPassword());
        if(user == null || !user.isLoggedIn()) {
            listener.getLogger().println("FAILED: to log in to youtrack");
            return true;
        }
        EnvVars environment = build.getEnvironment(listener);
        String buildName;
        if(getBuildName() == null || getBuildName().equals("")) {
            buildName = String.valueOf(build.getNumber());
        } else {

            buildName = environment.expand(getBuildName());

        }
        String inputBundleName =environment.expand(getBundleName());

        Command addedBuild = youTrackServer.addBuildToBundle(youTrackSite.getName(), user, inputBundleName, buildName);
        if(addedBuild.getStatus() == Command.Status.OK) {
            listener.getLogger().println("Added build " + buildName + " to bundle: " + inputBundleName);
        } else {
            listener.getLogger().println("FAILED: adding build " + buildName + " to bundle: " + inputBundleName);
        }

        youTrackCommandAction.addCommand(addedBuild);

        if(action != null) {
            List<String> issueIds = action.getIssueIds();
            boolean stable = build.getResult().isBetterOrEqualTo(Result.SUCCESS);
            boolean unstable = build.getResult().isBetterOrEqualTo(Result.UNSTABLE);


            if(stable || (isMarkFixedIfUnstable() && unstable)) {

                for (String issueId : issueIds) {
                Issue issue = new Issue(issueId);

                    String commandValue = "Fixed in build " + buildName;
                    Command command = youTrackServer.applyCommand(youTrackSite.getName(), user, issue, commandValue, null, null, !runSilently);
                    if(command.getStatus() == Command.Status.OK) {
                        listener.getLogger().println("Updated Fixed in build to " + buildName + " for " + issueId);
                    } else {
                        listener.getLogger().println("FAILED: updating Fixed in build to " + buildName + " for " + issueId);
                    }
                    youTrackCommandAction.addCommand(command);
                }
            }

        }

        return true;
    }

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }



        @Override
        public String getDisplayName() {
            return "YouTrack Build Updater";
        }

        @Override
        public Publisher newInstance(StaplerRequest req, JSONObject formData) throws FormException {
            return req.bindJSON(YouTrackBuildUpdater.class, formData);
        }

        @SuppressWarnings("UnusedDeclaration")
        public AutoCompletionCandidates doAutoCompleteBundleName(@AncestorInPath AbstractProject project, @QueryParameter String value) {
            YouTrackSite youTrackSite = YouTrackSite.get(project);
            AutoCompletionCandidates autoCompletionCandidates = new AutoCompletionCandidates();
            if(youTrackSite != null) {
                YouTrackServer youTrackServer = new YouTrackServer(youTrackSite.getUrl());
                User user = youTrackServer.login(youTrackSite.getUsername(), youTrackSite.getPassword());
                if(user != null) {
                    List<BuildBundle> bundles = youTrackServer.getBuildBundles(user);
                    for (BuildBundle bundle : bundles) {
                        if(bundle.getName().toLowerCase().contains(value.toLowerCase())) {
                            autoCompletionCandidates.add(bundle.getName());
                        }
                    }
                }
            }
            return autoCompletionCandidates;
        }


    }
}

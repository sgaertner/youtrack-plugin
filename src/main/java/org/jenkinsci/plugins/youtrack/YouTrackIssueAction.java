package org.jenkinsci.plugins.youtrack;

import com.google.gson.Gson;
import hudson.model.AbstractProject;
import hudson.model.Action;
import org.jenkinsci.plugins.youtrack.youtrackapi.Issue;
import org.jenkinsci.plugins.youtrack.youtrackapi.User;
import org.jenkinsci.plugins.youtrack.youtrackapi.YouTrackServer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import javax.servlet.ServletException;
import java.io.IOException;

/**
 * This is the action to get issue data from an YouTrack issue.
 */
public class YouTrackIssueAction implements Action {

    /**
     * Reference to the project to get YouTrack site info from.
     */
    public final AbstractProject project;

    /**
     * Constructs the action.
     *
     * @param project the project to get site info from.
     */
    public YouTrackIssueAction(AbstractProject project) {
        this.project = project;
    }

    /**
     * @return No icon.
     */
    public String getIconFileName() {
        return null;
    }

    /**
     * @return No display name.
     */
    public String getDisplayName() {
        return null;
    }

    /**
     * @return The url part for the action.
     */
    public String getUrlName() {
        return "youtrack";
    }

    /**
     * Generates a response containing issue data, but first logs in to YouTrack.
     *
     * @return the response.
     */
    @SuppressWarnings("UnusedDeclaration")
    public HttpResponse doIssue() {

        return new HttpResponse() {
            public void generateResponse(StaplerRequest req, StaplerResponse rsp, Object node) throws IOException, ServletException {

                String id = req.getParameter("id");
                YouTrackSite youTrackSite = YouTrackSite.get(project);
                if (youTrackSite == null) {
                    rsp.getWriter().write("YouTrack integration not set up for this project");
                    return;
                }

                YouTrackServer youTrackServer = new YouTrackServer(youTrackSite.getUrl());
                User user = youTrackServer.login(youTrackSite.getUsername(), youTrackSite.getPassword());
                Issue issue = youTrackServer.getIssue(user, id, youTrackSite.getStateFieldName());

                Document document = Jsoup.parse(issue.getDescription());
                Elements imageElements = document.select("img");
                for (Element imageElement : imageElements) {
                    String src = imageElement.attr("src");
                    if (!src.contains("://") && !src.startsWith("//")) {
                        String url = youTrackSite.getUrl();
                        String host = getDomainName(url);

                        imageElement.attr("src",  host + src);
                    }
                }

                issue.setDescription(document.html());

                Gson gson = new Gson();
                String json = gson.toJson(issue);
                rsp.getWriter().write(json);
            }
        };
    }

    private String getDomainName(String url) {
        int slashslash = url.indexOf("//") + 2;
        return url.substring(0, slashslash ) + url.substring(slashslash, url.indexOf('/', slashslash));
    }
}

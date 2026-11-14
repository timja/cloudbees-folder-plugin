package com.cloudbees.hudson.plugins.folder;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.sameInstance;

import hudson.model.FreeStyleProject;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import jenkins.persistence.PersistenceStore;
import jenkins.persistence.PersistenceStoreProvider;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * A child created in a folder by another controller is added to it, without reloading the folder's other children
 * (cloud-native Target 15).
 */
@WithJenkins
final class RemoteChildTest {

    static final InMemoryStore STORE = new InMemoryStore();

    @Test
    void childCreatedElsewhere(JenkinsRule r) throws Exception {
        Folder f = r.createProject(Folder.class, "f");
        FreeStyleProject sibling = f.createProject(FreeStyleProject.class, "sibling");
        Object siblingRuns = sibling.getLazyBuildMixIn()._getRuns(); // replaced whenever the sibling is (re)loaded
        STORE.as("other-controller")
                .write(
                        "jobs/f/jobs/remote/config.xml",
                        "<?xml version='1.1' encoding='UTF-8'?><project><description>made elsewhere</description></project>"
                                .getBytes(StandardCharsets.UTF_8),
                        PersistenceStore.ABSENT);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (f.getItem("remote") == null && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        FreeStyleProject remote = r.jenkins.getItemByFullName("f/remote", FreeStyleProject.class);
        assertThat(remote, notNullValue());
        assertThat(remote.getDescription(), is("made elsewhere"));
        assertThat(remote.getParent(), sameInstance(f));
        assertThat(f.getItem("sibling"), sameInstance(sibling));
        assertThat("the sibling was not reloaded", sibling.getLazyBuildMixIn()._getRuns(), sameInstance(siblingRuns));
    }

    @Test
    void folderChangedElsewhere(JenkinsRule r) throws Exception {
        Folder f = r.createProject(Folder.class, "changed");
        FreeStyleProject child = f.createProject(FreeStyleProject.class, "child");
        Object childRuns = child.getLazyBuildMixIn()._getRuns(); // replaced whenever the child is (re)loaded
        String xml = new String(STORE.read("jobs/changed/config.xml").content(), StandardCharsets.UTF_8);
        STORE.as("other-controller")
                .write(
                        "jobs/changed/config.xml",
                        xml.replaceFirst(
                                        "(<com\\.cloudbees\\.hudson\\.plugins\\.folder\\.Folder[^>]*>)",
                                        "$1<description>changed elsewhere</description>")
                                .getBytes(StandardCharsets.UTF_8),
                        PersistenceStore.ANY);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!"changed elsewhere".equals(f.getDescription()) && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(f.getDescription(), is("changed elsewhere"));
        assertThat(r.jenkins.getItem("changed"), sameInstance(f));
        assertThat(f.getItem("child"), sameInstance(child));
        assertThat("the child was not reloaded", child.getLazyBuildMixIn()._getRuns(), sameInstance(childRuns));
    }

    @TestExtension
    public static final class Provider implements PersistenceStoreProvider {
        @Override
        public PersistenceStore create() {
            return STORE;
        }
    }
}

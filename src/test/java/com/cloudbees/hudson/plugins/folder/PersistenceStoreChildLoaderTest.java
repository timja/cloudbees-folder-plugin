package com.cloudbees.hudson.plugins.folder;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;

import hudson.Util;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import java.io.File;
import jenkins.persistence.InMemoryPersistenceStore;
import jenkins.persistence.PersistenceStore;
import jenkins.persistence.PersistenceStoreProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

/**
 * Folder children whose configuration lives only in a persistence store are loaded,
 * as on a freshly started controller sharing the store with others.
 */
final class PersistenceStoreChildLoaderTest {

    static final InMemoryPersistenceStore STORE =
            new InMemoryPersistenceStore(key -> key.matches("jobs/[^/]+(/jobs/[^/]+)*/config\\.xml"));

    @RegisterExtension
    private final JenkinsSessionExtension js = new JenkinsSessionExtension();

    @Test
    void childrenLoadFromStore() throws Throwable {
        js.then(r -> {
            Folder top = r.createProject(Folder.class, "top");
            top.createProject(FreeStyleProject.class, "a").setDescription("stored");
            top.createProject(Folder.class, "nested").createProject(FreeStyleProject.class, "b");
            // fresh JENKINS_HOME: nothing local survives the restart
            Util.deleteRecursive(new File(r.jenkins.getRootDir(), "jobs"));
        });
        js.then(r -> {
            assertThat(
                    r.jenkins.getAllItems(Item.class).stream()
                            .map(Item::getFullName)
                            .toList(),
                    containsInAnyOrder("top", "top/a", "top/nested", "top/nested/b"));
            assertThat(
                    r.jenkins.getItemByFullName("top/a", FreeStyleProject.class).getDescription(), is("stored"));
        });
    }

    @TestExtension("childrenLoadFromStore")
    public static final class Provider implements PersistenceStoreProvider {
        @Override
        public PersistenceStore create() {
            return STORE;
        }
    }
}

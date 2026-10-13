package com.cloudbees.hudson.plugins.folder;

import hudson.Extension;
import hudson.model.TopLevelItem;
import hudson.util.CopyOnWriteMap;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.persistence.PersistenceStores;

@Extension
public final class DefaultChildLoader extends ChildLoader {

    private static final Logger LOGGER = Logger.getLogger(DefaultChildLoader.class.getName());

    @Override
    public <K, V extends TopLevelItem> Map<K, V> loadChildren(
            AbstractFolder<V> parent, File modulesDir, Function<? super V, ? extends K> key) {
        CopyOnWriteMap.Tree<K, V> configurations = new CopyOnWriteMap.Tree<>();
        if (!ensureDirExists(modulesDir, parent)) {
            return configurations;
        }

        File[] listed = modulesDir.listFiles(File::isDirectory);
        if (listed == null) {
            return configurations;
        }
        List<File> subdirs = new ArrayList<>(Arrays.asList(listed));
        try {
            // children which so far exist only in a persistence store (e.g. created by another controller)
            for (String name : PersistenceStores.storedItemNames(modulesDir)) {
                File subdir = new File(modulesDir, name);
                if (!subdirs.contains(subdir)) {
                    Files.createDirectories(subdir.toPath());
                    subdirs.add(subdir);
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not list stored children of " + parent.getFullName(), e);
        }

        Map<String, V> byDirName = getItemsByDirName(parent);
        for (File subdir : subdirs) {
            // Try to retain the identity of an existing child object if we can.
            V existingItem = byDirName.get(subdir.getName());
            V item = loadItem(parent, subdir, existingItem);
            if (item != null) {
                configurations.put(key.apply(item), item);
            }
        }

        return configurations;
    }
}

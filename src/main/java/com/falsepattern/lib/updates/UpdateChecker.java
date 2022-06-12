package com.falsepattern.lib.updates;

import com.falsepattern.json.node.JsonNode;
import com.falsepattern.lib.StableAPI;
import com.falsepattern.lib.dependencies.DependencyLoader;
import com.falsepattern.lib.dependencies.SemanticVersion;
import com.falsepattern.lib.internal.FalsePatternLib;
import com.falsepattern.lib.internal.Internet;
import com.falsepattern.lib.internal.LibraryConfig;
import com.falsepattern.lib.internal.Tags;
import com.falsepattern.lib.text.FormattedText;
import com.falsepattern.lib.util.AsyncUtil;
import cpw.mods.fml.common.Loader;
import lombok.val;
import net.minecraft.client.resources.I18n;
import net.minecraft.event.ClickEvent;
import net.minecraft.util.IChatComponent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@StableAPI(since = "0.8.0")
public class UpdateChecker {
    private static final AtomicInteger jsonLibraryLoaded = new AtomicInteger(0);
    /**
     * Same this as {@link #fetchUpdates(String)}, but defers the check to a different thread. Useful for asynchronous
     * update checks, if you don't want to block loading.
     * @param url The URL to check
     * @return A future that will contain the update info about mods that were both available on the URL and installed
     */
    public static Future<List<ModUpdateInfo>> fetchUpdatesAsync(String url) {
        return AsyncUtil.asyncWorker.submit(() -> fetchUpdates(url));
    }

    /**
     * Checks for updates. The URL should be a JSON file that contains a list of mods, each with a mod ID, one or more
     * versions, and a URL for the user to check for updates in case the current and latest versions are different.
     * The JSON file must have the following format:
     * <pre>{@code
     *  [
     *      {
     *          "modID": "modid",
     *          "latestVersion": ["1.0.0", "1.0.0-foo"],
     *          "updateURL": "https://example.com/mods/mymod"
     *      },
     *      {
     *          "modID": "modid2",
     *          "latestVersion": ["0.2.0", "0.3.0-alpha"],
     *          "updateURL": "https://example.com/mods/mymod2"
     *      },
     *      ...etc, one json object per mod.
     *  ]
     * }</pre>
     * @param url The URL to check
     * @return A list of mods that were both available on the URL and installed
     */
    public static List<ModUpdateInfo> fetchUpdates(String url) {
        if (!LibraryConfig.ENABLE_UPDATE_CHECKER) {
            return null;
        }
        URL URL;
        try {
            URL = new URL(url);
        } catch (MalformedURLException e) {
            FalsePatternLib.getLog().error("Invalid URL: {}", url, e);
            return null;
        }
        switch (jsonLibraryLoaded.get()) {
            case 0:
                DependencyLoader.addMavenRepo("https://maven.falsepattern.com/");
                try {
                    DependencyLoader.builder()
                                    .loadingModId(Tags.MODID)
                                    .groupId("com.falsepattern")
                                    .artifactId("json")
                                    .minVersion(new SemanticVersion(0, 4, 0))
                                    .maxVersion(new SemanticVersion(0, Integer.MAX_VALUE, Integer.MAX_VALUE))
                                    .preferredVersion(new SemanticVersion(0, 4, 1))
                                    .build();
                } catch (Exception e) {
                    FalsePatternLib.getLog().error("Failed to load json library for update checker!", e);
                    jsonLibraryLoaded.set(-1);
                    return null;
                }
                jsonLibraryLoaded.set(1);
                break;
            case -1:
                return null;
        }
        AtomicReference<String> loadedData = new AtomicReference<>(null);
        Internet.connect(URL, (ex) -> FalsePatternLib.getLog().warn("Failed to check for updates from URL {}", url, ex), (input) -> {
            val data = new ByteArrayOutputStream();
            try {
                Internet.transferAndClose(input, data);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            loadedData.set(data.toString());
        });
        if (loadedData.get() == null) return null;
        val result = new ArrayList<ModUpdateInfo>();
        val parsed = JsonNode.parse(loadedData.get());
        List<JsonNode> modList;
        if (parsed.isList()) {
            modList = parsed.getJavaList();
        } else {
            modList = Collections.singletonList(parsed);
        }
        val installedMods = Loader.instance().getIndexedModList();
        for (val node: modList) {
            if (!node.isObject()) continue;
            if (!node.containsKey("modid")) continue;
            if (!node.containsKey("latestVersion")) continue;
            val modid = node.getString("modid");
            if (!installedMods.containsKey(modid)) continue;
            val mod = installedMods.get(modid);
            val latestVersionsNode = node.get("latestVersion");
            List<String> latestVersions;
            if (latestVersionsNode.isString()) {
                latestVersions = Collections.singletonList(latestVersionsNode.stringValue());
            } else if (latestVersionsNode.isList()) {
                latestVersions = new ArrayList<>();
                for (val version: latestVersionsNode.getJavaList()) {
                    if (!version.isString()) continue;
                    latestVersions.add(version.stringValue());
                }
            } else {
                continue;
            }
            val currentVersion = mod.getVersion();
            if (latestVersions.contains(currentVersion)) continue;
            val updateURL = node.containsKey("updateURL") && node.get("updateURL").isString() ? node.getString("updateURL") : "";
            result.add(new ModUpdateInfo(modid, currentVersion, latestVersions.get(0), updateURL));
        }
        return result;
    }

    /**
     * Formats the raw list of updates into lines of chat messages you can send to players.
     * @param initiator Who/what/which mod did this update check
     * @param updates The list of updates to convert
     * @return A list of chat messages that can be sent to players
     */
    public static List<IChatComponent> updateListToChatMessages(String initiator, List<ModUpdateInfo> updates) {
        if (updates == null || updates.size() == 0)
            return null;
        val updateText = new ArrayList<IChatComponent>(FormattedText.parse(I18n.format("falsepatternlib.chat.updatesavailable", initiator)).toChatText());
        val mods = Loader.instance().getIndexedModList();
        for (val update : updates) {
            val mod = mods.get(update.modID);
            updateText.addAll(FormattedText.parse(I18n.format("falsepatternlib.chat.modname", mod.getName())).toChatText());
            updateText.addAll(FormattedText.parse(I18n.format("falsepatternlib.chat.currentversion", update.currentVersion)).toChatText());
            updateText.addAll(FormattedText.parse(I18n.format("falsepatternlib.chat.latestversion", update.latestVersion)).toChatText());
            if (!update.updateURL.isEmpty()) {
                val pre = FormattedText.parse(I18n.format("falsepatternlib.chat.updateurlpre")).toChatText();
                val link = FormattedText.parse(I18n.format("falsepatternlib.chat.updateurl")).toChatText();
                val post = FormattedText.parse(I18n.format("falsepatternlib.chat.updateurlpost")).toChatText();
                pre.get(pre.size() - 1).appendSibling(link.get(0));
                link.get(link.size() - 1).appendSibling(post.get(0));
                for (val l : link) {
                    l.getChatStyle().setChatClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, update.updateURL));
                }
                link.remove(0);
                post.remove(0);
                updateText.addAll(pre);
                updateText.addAll(link);
                updateText.addAll(post);
            }
        }
        return updateText;
    }
}
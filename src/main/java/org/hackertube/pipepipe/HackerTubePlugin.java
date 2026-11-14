package org.hackertube.pipepipe;

import org.hackertube.pipepipe.config.HackerTubePrefs;
import org.hackertube.pipepipe.engine.HackerTubeRepository;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.plugin.PipePipePlugin;
import org.schabi.newpipe.extractor.plugin.PluginContext;

import javax.annotation.Nonnull;

/**
 * Entry point registered in {@code plugin.json} ({@code entryClass}).
 */
public final class HackerTubePlugin implements PipePipePlugin {

    public HackerTubePlugin() {
    }

    @Nonnull
    @Override
    public StreamingService createService(final int serviceId,
                                          @Nonnull final PluginContext context) {
        final HackerTubePrefs prefs = new HackerTubePrefs(context.getPreferences());
        final HackerTubeRepository repository = new HackerTubeRepository(prefs);
        return new HackerTubeService(serviceId, prefs, repository);
    }
}

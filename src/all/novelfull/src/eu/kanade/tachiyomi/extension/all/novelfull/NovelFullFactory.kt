package eu.kanade.tachiyomi.extension.all.novelfull

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory

class NovelFullFactory : SourceFactory {
    /**
     * Create a new copy of the sources
     * @return The created sources
     */
    override fun createSources(): List<Source> {
        return listOf(
            NovelFull(),
            NovelFullId(),
            NovelFullZh(),
        )
    }
}

class NovelFull : NovelFullGlobal("en")
class NovelFullId : NovelFullGlobal("id", supportsSearch = false)
class NovelFullZh : NovelFullGlobal("zh", supportsSearch = false)

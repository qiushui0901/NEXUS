package com.example.requirementrag.wiki;

import com.example.requirementrag.config.WikiProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WikiRepositoryCacheTest {
    @TempDir
    Path temp;

    @Test
    void keepsPublishedVersionCachedUntilTargetedInvalidation() throws Exception {
        Path source = Path.of("data/wiki/immortal-game-service/4.1.5");
        Path target = temp.resolve("demo/4.1.5");
        Files.createDirectories(target.resolve("pages"));
        Files.copy(source.resolve("index.json"), target.resolve("index.json"));
        String feature = "version-4.1.5-overview";
        Files.copy(source.resolve("pages").resolve(feature + ".json"),
                target.resolve("pages").resolve(feature + ".json"));
        WikiRepository repository = new WikiRepository(JsonMapper.builder().build(),
                new WikiProperties(temp.toString(), temp.resolve("sources").toString(), null, 60, 20));

        WikiModels.Page originalPage = repository.getPage("demo", "4.1.5", feature);
        WikiModels.VersionIndex originalIndex = repository.getIndex("demo", "4.1.5");
        Files.writeString(target.resolve("pages").resolve(feature + ".json"),
                Files.readString(target.resolve("pages").resolve(feature + ".json"), StandardCharsets.UTF_8)
                        .replace("4.1.5 版本概览", "缓存失效后的标题"),
                StandardCharsets.UTF_8);
        Files.writeString(target.resolve("index.json"),
                Files.readString(target.resolve("index.json"), StandardCharsets.UTF_8)
                        .replace("封神服务端", "缓存失效后的项目"),
                StandardCharsets.UTF_8);

        assertThat(repository.getPage("demo", "4.1.5", feature).title()).isEqualTo(originalPage.title());
        assertThat(repository.getIndex("demo", "4.1.5").projectName()).isEqualTo(originalIndex.projectName());

        repository.invalidate("demo", "4.1.5");

        assertThat(repository.getPage("demo", "4.1.5", feature).title()).isEqualTo("缓存失效后的标题");
        assertThat(repository.getIndex("demo", "4.1.5").projectName()).isEqualTo("缓存失效后的项目");
    }
}

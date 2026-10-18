package com.linklens.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HtmlTitleTest {

    @Test
    void extractsAndCleansTheTitle() {
        assertThat(HtmlTitle.extract("<html><head><TITLE lang=\"en\">\n  Spring   Boot\n</TITLE></head>"))
                .isEqualTo("Spring Boot");
    }

    @Test
    void decodesCommonEntities() {
        assertThat(HtmlTitle.extract("<title>Tom &amp; Jerry &#8212; &#x2603; &quot;hi&quot; &unknown;</title>"))
                .isEqualTo("Tom & Jerry — ☃ \"hi\" &unknown;");
    }

    @Test
    void missingEmptyOrUnterminatedTitlesAreNull() {
        assertThat(HtmlTitle.extract("<html><body>no title</body>")).isNull();
        assertThat(HtmlTitle.extract("<title>   </title>")).isNull();
        assertThat(HtmlTitle.extract("<title>never closed")).isNull();
        assertThat(HtmlTitle.extract(null)).isNull();
    }

    @Test
    void capsTheLength() {
        String title = HtmlTitle.extract("<title>" + "a".repeat(1000) + "</title>");
        assertThat(title).hasSize(HtmlTitle.MAX_LENGTH).endsWith("…");
    }

    @Test
    void keepsMarkupInTitlesAsText() {
        // The value is escaped when rendered; extraction must not "clean" it into something else.
        assertThat(HtmlTitle.extract("<title>&lt;script&gt;alert(1)&lt;/script&gt;</title>")).isEqualTo("<script>alert(1)</script>");
    }
}

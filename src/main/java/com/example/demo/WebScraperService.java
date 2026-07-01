package com.example.demo;

import com.example.dto.UnifiedAnalysisResponse;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDateTime;

@Service
@Slf4j
public class WebScraperService {

    @Value("${web.scraper.timeout:10000}")
    private int timeoutMs;

    @Value("${web.scraper.userAgent:Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36}")
    private String userAgent;

    public Mono<WebScrapingResult> scrapeUrl(String url) {
        return Mono.fromCallable(() -> {
            long startTime = System.currentTimeMillis();
            log.info("Starting URL scraping: {}", url);
            
            try {
                Document doc = Jsoup.connect(url)
                        .userAgent(userAgent)
                        .timeout(timeoutMs)
                        .followRedirects(true)
                        .get();

                long responseTime = System.currentTimeMillis() - startTime;
                
                // Extract title
                String title = doc.title();
                if (title.isEmpty()) {
                    title = extractTitleFromH1(doc);
                }
                
                // Extract main content
                String content = extractMainContent(doc);
                
                // Build metadata
                UnifiedAnalysisResponse.UrlMetadata metadata = UnifiedAnalysisResponse.UrlMetadata.builder()
                        .originalUrl(url)
                        .finalUrl(doc.location())
                        .domain(extractDomain(doc.location()))
                        .title(title)
                        .responseTime((int) responseTime)
                        .isAccessible(true)
                        .build();

                log.info("URL scraping completed: {} chars extracted in {}ms", 
                        content.length(), responseTime);

                return new WebScrapingResult(content, metadata);
                
            } catch (IOException e) {
                log.error("Failed to scrape URL: {}", url, e);
                UnifiedAnalysisResponse.UrlMetadata metadata = UnifiedAnalysisResponse.UrlMetadata.builder()
                        .originalUrl(url)
                        .finalUrl(url)
                        .domain(extractDomain(url))
                        .title("")
                        .responseTime((int) (System.currentTimeMillis() - startTime))
                        .isAccessible(false)
                        .build();
                
                return new WebScrapingResult("", metadata);
            }
        })
        .onErrorResume(e -> {
            log.error("Error during URL scraping: {}", e.getMessage());
            UnifiedAnalysisResponse.UrlMetadata metadata = UnifiedAnalysisResponse.UrlMetadata.builder()
                    .originalUrl(url)
                    .finalUrl(url)
                    .domain(extractDomain(url))
                    .title("")
                    .responseTime(0)
                    .isAccessible(false)
                    .build();
            
            return Mono.just(new WebScrapingResult("", metadata));
        });
    }

    private String extractMainContent(Document doc) {
        StringBuilder content = new StringBuilder();
        
        // Try to find main content areas
        if (doc.select("main").size() > 0) {
            content.append(doc.select("main").text());
        } else if (doc.select("article").size() > 0) {
            content.append(doc.select("article").text());
        } else if (doc.select(".content, .main-content, .post-content").size() > 0) {
            content.append(doc.select(".content, .main-content, .post-content").text());
        } else {
            // Fallback to body content
            content.append(doc.body().text());
        }
        
        // Clean up the content
        return cleanText(content.toString());
    }

    private String extractTitleFromH1(Document doc) {
        return doc.select("h1").first() != null ? doc.select("h1").first().text() : "";
    }

    private String cleanText(String text) {
        return text
                .replaceAll("\\s+", " ")
                .replaceAll("\\n+", "\n")
                .trim();
    }

    private String extractDomain(String url) {
        try {
            URI uri = new URI(url);
            return uri.getHost();
        } catch (URISyntaxException e) {
            return url;
        }
    }

    public record WebScrapingResult(String content, UnifiedAnalysisResponse.UrlMetadata metadata) {}
}

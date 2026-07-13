package com.example.demo;

import com.example.dto.UnifiedAnalysisResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Mono;

import java.io.IOException;

@Service
@Slf4j
public class ContentRouterService {

    private final FileParserService fileParserService;
    private final WebScraperService webScraperService;

    public ContentRouterService(FileParserService fileParserService, WebScraperService webScraperService) {
        this.fileParserService = fileParserService;
        this.webScraperService = webScraperService;
    }

    /**
     * Extracts content from the provided input (text, file, or URL) and returns
     * a unified result with metadata about the extraction process.
     */
    public Mono<ContentExtractionResult> extractContent(String text, MultipartFile file, String url) {
        // Determine input type and route to appropriate extractor
        if (text != null && !text.isBlank()) {
            return extractFromText(text);
        } else if (file != null && !file.isEmpty()) {
            return extractFromFile(file);
        } else if (url != null && !url.isBlank()) {
            return extractFromUrl(url);
        } else {
            return Mono.error(new IllegalArgumentException("No valid input provided"));
        }
    }

    private Mono<ContentExtractionResult> extractFromText(String text) {
        log.info("Extracting content from direct text input ({} chars)", text.length());
        
        ContentExtractionResult result = new ContentExtractionResult(
                UnifiedAnalysisResponse.InputType.TEXT,
                text,
                "text/plain",
                "direct_input",
                null,
                null
        );
        
        return Mono.just(result);
    }

    private Mono<ContentExtractionResult> extractFromFile(MultipartFile file) {
        log.info("Extracting content from file: {}", file.getOriginalFilename());
        
        return Mono.fromCallable(() -> {
            try {
                String content = fileParserService.extractText(file);
                String filename = file.getOriginalFilename();
                String extension = getFileExtension(filename);
                
                UnifiedAnalysisResponse.FileMetadata metadata = UnifiedAnalysisResponse.FileMetadata.builder()
                        .filename(filename)
                        .fileSize(file.getSize())
                        .fileExtension(extension)
                        .detectedMimeType(file.getContentType())
                        .build();

                return new ContentExtractionResult(
                        UnifiedAnalysisResponse.InputType.FILE,
                        content,
                        file.getContentType(),
                        "file_parser",
                        metadata,
                        null
                );
            } catch (IOException e) {
                log.error("Failed to extract content from file: {}", file.getOriginalFilename(), e);
                throw new RuntimeException("Failed to extract content from file: " + e.getMessage(), e);
            }
        });
    }

    private Mono<ContentExtractionResult> extractFromUrl(String url) {
        log.info("Extracting content from URL: {}", url);
        
        return webScraperService.scrapeUrl(url)
                .map(result -> new ContentExtractionResult(
                        UnifiedAnalysisResponse.InputType.URL,
                        result.content(),
                        "text/html",
                        "web_scraper",
                        null,
                        result.metadata()
                ));
    }

    private String getFileExtension(String filename) {
        if (filename == null || filename.isEmpty()) {
            return "";
        }
        int lastDotIndex = filename.lastIndexOf('.');
        return lastDotIndex == -1 ? "" : filename.substring(lastDotIndex + 1);
    }

    /**
     * Record representing the result of content extraction with all necessary metadata
     */
    public record ContentExtractionResult(
            UnifiedAnalysisResponse.InputType inputType,
            String content,
            String contentType,
            String extractionMethod,
            UnifiedAnalysisResponse.FileMetadata fileMetadata,
            UnifiedAnalysisResponse.UrlMetadata urlMetadata
    ) {}
}

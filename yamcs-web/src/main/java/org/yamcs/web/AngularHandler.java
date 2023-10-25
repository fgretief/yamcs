package org.yamcs.web;

import static io.netty.handler.codec.http.HttpHeaderNames.CACHE_CONTROL;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static org.yamcs.web.WebFileDeployer.PATH_INDEX;
import static org.yamcs.web.WebFileDeployer.PATH_NGSW;
import static org.yamcs.web.WebFileDeployer.PATH_WEBMANIFEST;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.yamcs.YConfiguration;
import org.yamcs.http.HandlerContext;
import org.yamcs.http.HttpServer;
import org.yamcs.http.InternalServerErrorException;
import org.yamcs.http.NotFoundException;
import org.yamcs.http.StaticFileHandler;

import com.google.common.io.ByteStreams;

import io.netty.buffer.ByteBufOutputStream;
import io.netty.handler.codec.http.DefaultFullHttpResponse;

/**
 * Serves the Yamcs UI, which is an Angular web application.
 * <p>
 * This class mostly serves static files generated by the Angular compiler, but will insert some dynamic elements.
 * Specifically:
 * <ul>
 * <li>Allow hosting on custom context path, without needing a recompile.
 * <li>Serve PWA webmanifest for local installation.
 * <li>Serve index.html with dynamically inserted configuration elements.
 * <li>Serve custom logo, without needing a recompile.
 * </ul>
 */
public class AngularHandler extends StaticFileHandler {

    // Dev only for now
    private static boolean PWA = false;

    private Path indexFile;
    private Path webManifestFile;
    private Path ngswFile;

    private String logo;
    private Path logoFile;

    public AngularHandler(YConfiguration config, HttpServer httpServer, Path mainDirectory,
            List<Path> extraStaticRoots) {
        super("", Stream.concat(Stream.of(mainDirectory), extraStaticRoots.stream())
                .collect(Collectors.toList()));
        indexFile = mainDirectory.resolve(PATH_INDEX);
        webManifestFile = mainDirectory.resolve(PATH_WEBMANIFEST);
        ngswFile = mainDirectory.resolve(PATH_NGSW);

        if (config.containsKey("logo")) {
            logoFile = Path.of(config.getString("logo"));
            logo = logoFile.getFileName().toString();
        }
    }

    @Override
    public void handle(HandlerContext ctx) {
        var filePath = getFilePath(ctx);

        // Serve custom brand, if configured
        if (logo != null && logo.equals(filePath)) {
            serveLogo(ctx);
            return;
        }

        switch (filePath) {
        case PATH_WEBMANIFEST:
            if (!PWA) {
                throw new NotFoundException();
            }
            serveUncached(ctx, webManifestFile, "application/manifest+json");
            return;
        case PATH_NGSW:
            if (!PWA) {
                throw new NotFoundException();
            }
            serveUncached(ctx, ngswFile, "application/json");
            return;
        }

        // Try to serve a static file
        var file = locateFile(filePath);
        if (file != null && !filePath.isEmpty()) {
            super.handle(ctx);
            return;
        }

        // Set-up HTML5 deep-linking:
        // Catch any non-handled URL and make it return the contents of our index.html
        // This will cause initialization of the Angular app on any requested path. The
        // Angular router will interpret this and do client-side routing as needed.
        serveUncached(ctx, indexFile, "text/html");
    }

    /**
     * Sends a rendered template, while recommend clients to not cache it. We hash all of our web files, and this
     * reduces likelihood of attempting to load the app from an outdated index.html
     */
    private void serveUncached(HandlerContext ctx, Path file, String contentType) {
        ctx.requireGET();

        var body = ctx.createByteBuf();
        try (var fileIn = Files.newInputStream(file);
                var bufOut = new ByteBufOutputStream(body)) {
            ByteStreams.copy(fileIn, bufOut);
        } catch (NoSuchFileException e) {
            throw new NotFoundException(e);
        } catch (IOException e) {
            throw new InternalServerErrorException(e);
        }

        var response = new DefaultFullHttpResponse(HTTP_1_1, OK, body);
        response.headers().set(CONTENT_TYPE, contentType);
        response.headers().set(CONTENT_LENGTH, body.readableBytes());
        response.headers().set(CACHE_CONTROL, "no-store, must-revalidate");
        ctx.sendResponse(response);
    }

    private void serveLogo(HandlerContext ctx) {
        ctx.requireGET();

        var body = ctx.createByteBuf();
        try (var in = Files.newInputStream(logoFile); var out = new ByteBufOutputStream(body)) {
            ByteStreams.copy(in, out);
        } catch (NoSuchFileException e) {
            throw new NotFoundException();
        } catch (IOException e) {
            throw new InternalServerErrorException(e);
        }

        var response = new DefaultFullHttpResponse(HTTP_1_1, OK, body);
        response.headers().set(CONTENT_TYPE, MIME.getMimetype(logoFile));
        response.headers().set(CONTENT_LENGTH, body.readableBytes());
        response.headers().set(CACHE_CONTROL, "private, max-age=86400");
        ctx.sendResponse(response);
    }
}
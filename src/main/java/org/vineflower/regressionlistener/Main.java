package org.vineflower.regressionlistener;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.stream.Stream;

public class Main {
    private static final LinkedBlockingDeque<JsonObject> TASKS = new LinkedBlockingDeque<>();
    private static final String SECRET = System.getProperty("SEC_KEY");
    private static final String DIFF_LOC = System.getProperty("DIFF_LOC");
    private static final String DIFF_URL = System.getProperty("DIFF_URL");
    private static final String AFFIRM_URL = System.getProperty("AFFIRM_URL");
    private static final String AFFIRM_ICO = System.getProperty("AFFIRM_ICO");
    private static final String JAVA = System.getProperty("JAVA_BIN", "java");
    private static final String JAVA_MEM = System.getProperty("JAVA_MEM", "3000M");
    private static final int PORT = Integer.getInteger("PORT", 8000);

    public static void main(String[] args) throws Exception {
        if (SECRET == null) {
            System.out.println("Missing -DSEC_KEY=...");
            return;
        }

        if (DIFF_LOC == null) {
            System.out.println("Missing -DDIFF_LOC=...");
            return;
        }

        if (DIFF_URL == null) {
            System.out.println("Missing -DDIFF_URL=...");
            return;
        }

        Path manifest = Path.of("dlmanifest.txt");
        if (!Files.exists(manifest)) {
            System.out.println("Missing dlmanifest.txt");
            return;
        }

        if (AFFIRM_URL == null || AFFIRM_ICO == null) {
            System.out.println("WARNING: affirmation webhook isn't set up!");
        }

        System.out.println("Ensuring manifest is complete...");
        downloadManifest();

        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", PORT), 0);
        server.createContext("/", new MyHandler());
        server.setExecutor(null); // creates a default executor
        server.start();
        Thread t = new Thread(Main::doWork);
        t.start();
        System.out.println("Server started and listening...");
    }

    private static void downloadManifest() throws Exception {
        Path manifest = Path.of("dlmanifest.txt");

        Path input = Path.of("input");
        Files.createDirectories(input);

        // Check what jars we already have
        List<String> existing = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(input)) {
            stream.forEach(p -> existing.add(p.getFileName().toString()));
        }

        // Download jars from manifest
        List<String> manifestLines = Files.readAllLines(manifest);
        for (String line : manifestLines) {
            String fileName = line.substring(line.lastIndexOf('/') + 1);

            if (existing.stream().anyMatch(s -> s.contains(fileName))) {
                continue;
            }

            System.out.println("Downloading " + fileName + " from " + line);

            URL website = new URL(line);
            ReadableByteChannel rbc = Channels.newChannel(website.openStream());

            try (FileOutputStream fos = new FileOutputStream("input/" + fileName)) {
                fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
            }
        }
    }

    public static void doWork() {
        while (true) {
            try {
                // Block until a new task is found
                JsonObject obj = TASKS.take();
                System.out.println("Recieved task!");

                String before = obj.getAsJsonPrimitive("before").getAsString();
                String after = obj.getAsJsonPrimitive("after").getAsString();

                String url = obj.getAsJsonObject("repository").getAsJsonPrimitive("clone_url").getAsString();

                System.out.println("Repository is " + url + " and we are finding the diff between " + before + " and " + after);

                Path scratch = Paths.get("scratch");
                delete(Paths.get("scratch"));
                Files.createDirectories(scratch);

                Path repo = scratch.resolve("repo");
                Path output = scratch.resolve("output");
                Files.createDirectories(repo);
                Files.createDirectories(output);

                System.out.println("Setup scratch directories, now obtaining repo!");

                run(scratch, "git", "clone", url, "repo");
                run(repo, "git", "checkout", after);
                run(repo, scratch.resolve("lastcommit"), "git", "show", "--format=%P", after);
                List<String> lc = Files.readAllLines(scratch.resolve("lastcommit"));
                if (lc.size() >= 1) {
                    String obefore = lc.get(0);
                    if (!before.equals(obefore)) {
                        System.out.println("Before commit purported to be " + before + " but is actually " + obefore);
                        before = obefore;
                    }
                }
                run(repo, "git", "checkout", before);


                System.out.println("Building before jar!");
                run(repo, "chmod", "+x", "gradlew");
                run(repo, "./gradlew", "build", "-x", "test");

                System.out.println("Setting up output repo");
                // Setup output

                run(output, "git", "init");

                Path libs = repo.resolve("build").resolve("libs");

                List<Path> vfJars = new ArrayList<>();
                findJars(libs, vfJars);

                System.out.println("Decompiling before!");

                run(Path.of("."), JAVA, "-Xmx" + JAVA_MEM, "-cp", "jardecomp.jar:" + vfJars.get(0).toString(), "org.vineflower.jardecomp.Main");

                System.out.println("Adding to output repo!");

                // Run these quietly to prevent logspam

                runQuietly(output, "git", "add", ".");
                // Seems this one doesn't work when quiet??
                run(output, "git", "commit", "-m", "initial");

                System.out.println("Deleting libs!");

                delete(libs);

                System.out.println("Checking out and building after!");

                run(repo, "git", "checkout", after);
                run(repo, "./gradlew", "build", "-x", "test");
                vfJars.clear();
                findJars(libs, vfJars);

                System.out.println("Decompiling after!");
                run(Path.of("."), JAVA, "-Xmx" + JAVA_MEM, "-cp", "jardecomp.jar:" + vfJars.get(0).toString(), "org.vineflower.jardecomp.Main");

                runQuietly(output, "git", "add", ".");

                System.out.println("Creating and writing diff!");
                run(output, output.resolve("out.diff"), "git", "diff", "HEAD");
                Files.copy(output.resolve("out.diff"), Paths.get(DIFF_LOC, after + ".diff"), StandardCopyOption.REPLACE_EXISTING);
                System.out.println("Wrote " + DIFF_LOC + "/" + after + ".diff!");

                sendAffirm(DIFF_URL + after + ".diff", after, obj.getAsJsonObject("repository").getAsJsonPrimitive("html_url").getAsString());

                System.out.println("Cleaning up scratch directory!");
                delete(scratch);
            } catch (Exception e) {
                e.printStackTrace();

                try {
                    delete(Paths.get("scratch"));
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
            }
        }
    }

    public static void sendAffirm(String url, String after, String ghUrl) throws Exception {
        if (AFFIRM_URL == null || AFFIRM_ICO == null) {
            return;
        }

        System.out.println("Sending affirmation of the diff");
        HttpClient client = HttpClient.newHttpClient();

        JsonObject obj = new JsonObject();
        obj.addProperty("username", "Diff machine");
        obj.addProperty("avatar_url", AFFIRM_ICO);
        obj.addProperty("content", "Diff for commit [" + after + "](<" + ghUrl + "/commit/" + after + ">) is published at: " + url);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(AFFIRM_URL))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(obj.toString()))
                .build();

        HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString());

        System.out.println("Response:");
        System.out.println(response.headers());
        System.out.println(response.statusCode());
        System.out.println(response.body());
    }

    private static void findJars(Path libs, List<Path> vfJars) throws IOException {
        Stream<Path> walk = Files.walk(libs);
        try (walk) {
            walk.forEach(p -> {
                if (p.toString().endsWith(".jar")) {
                    String name = p.getFileName().toString();
                    if (!name.contains("slim") && !name.contains("javadoc") && !name.contains("sources") && !name.contains("all") && !name.contains("test-fixtures")) {
                        vfJars.add(p);
                    }
                }
            });
        }

        if (vfJars.size() != 1) {
            System.out.println("Found more or less than 1 VF jar!! " + vfJars);
        }
    }

    private static void delete(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }

        Stream<Path> walk = Files.walk(dir);
        try (walk) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private static void run(Path dir, String... commands) throws Exception {
        System.out.println("Running command: " + Arrays.toString(commands) + " in " + dir.toString());
        new ProcessBuilder(commands)
                .directory(dir.toFile())
                .inheritIO().start().waitFor();
    }

    private static void runQuietly(Path dir, String... commands) throws Exception {
        System.out.println("Running command quietly: " + Arrays.toString(commands) + " in " + dir.toString());
        new ProcessBuilder(commands)
                .directory(dir.toFile())
                .start().waitFor();
    }

    private static void run(Path dir, Path redir, String... commands) throws Exception {
        System.out.println("Running command: " + Arrays.toString(commands) + " in " + dir.toString() + " while piping to " + redir.toString());
        new ProcessBuilder(commands)
                .directory(dir.toFile())
                .redirectOutput(redir.toFile()).start().waitFor();
    }

    private static class MyHandler implements HttpHandler {
        private static boolean validate(String key, String msg, String hash) {
            try {
                Mac mac = Mac.getInstance("HmacSHA256");

                mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));

                String string = string(mac.doFinal(msg.getBytes(StandardCharsets.UTF_8)));
                return hash.equals("sha256=" + string);
            } catch (Exception e) {
                System.out.println("Validation failure!!");
                e.printStackTrace();

                return false;
            }
        }

        public static String string(byte[] a) {
            StringBuilder sb = new StringBuilder();
            for (byte b : a) {
                sb.append(String.format("%02x", b));
            }

            return sb.toString();
        }

        @Override
        public void handle(HttpExchange t) throws IOException {
            System.out.println("Received something!");
            Headers headers = t.getRequestHeaders();

            String mtd = t.getRequestMethod();
            if ("POST".equals(mtd)) {
                System.out.println("It's a POST");
                List<String> sig = headers.get("X-Hub-Signature-256");
                String body = new String(t.getRequestBody().readAllBytes());

                if (sig != null && !sig.isEmpty() && validate(SECRET, body, sig.get(0))) {
                    try {
                        JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                        System.out.println("Recieved: " + json);
                        if (json.has("before") && json.has("after")) {
                            TASKS.add(json);
                            System.out.println("Successfully added a task");

                            respond(t, "Processing your request!", 200);
                        } else {
                            System.out.println("Not a push!");
                            respond(t, "Ack, but not a push", 200);
                        }
                    } catch (Exception e) {
                        System.out.println("Malformed request??");
                        e.printStackTrace();
                        respond(t, "Malformed request!", 400);
                    }
                } else {
                    System.out.println("signature was not validated!");
                    respond(t, "You're not authorized!", 403);
                }
            } else if ("GET".equals(mtd)) {
                System.out.println("It's a GET");
                respond(t, "You've found the Vineflower diff machine! However, I don't allow GET requests!", 200);
            } else {
                System.out.println("???? " + mtd);
                respond(t, "I have no idea who you are, I cannot acknowledge", 403);
            }
        }

        private static void respond(HttpExchange t, String response, int code) throws IOException {
            System.out.println("Sent response [" + code + "] " + response);
            t.sendResponseHeaders(code, response.length());
            OutputStream os = t.getResponseBody();
            os.write(response.getBytes());
            os.close();
        }
    }
}
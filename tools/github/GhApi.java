import java.net.URI;
import java.net.http.*;
import java.nio.file.*;

/** Minimal GitHub API caller for the release step: needs PATCH and POST, which GhGet does not. */
public class GhApi {
  public static void main(String[] a) throws Exception {
    String method = a[0], url = a[1], token = a[2];
    String body = a.length > 3 ? Files.readString(Path.of(a[3])) : null;

    HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
        .header("Authorization", "Bearer " + token)
        .header("Accept", "application/vnd.github+json")
        .header("User-Agent", "linksi-build");
    if (body == null) {
      b.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      b.header("Content-Type", "application/json")
       .method(method, HttpRequest.BodyPublishers.ofString(body));
    }
    HttpResponse<String> r = c.send(b.build(), HttpResponse.BodyHandlers.ofString());
    System.out.println("STATUS=" + r.statusCode());
    String out = r.body();
    System.out.println(out.length() > 900 ? out.substring(0, 900) + "..." : out);
  }
}

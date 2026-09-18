import java.net.URI;
import java.net.http.*;
import java.nio.file.*;

/** Uploads one release asset. Binary body, so it needs its own class rather than the JSON helper. */
public class GhUpload {
  public static void main(String[] a) throws Exception {
    String uploadUrl = a[0], token = a[1], file = a[2], name = a[3];
    String type = a.length > 4 ? a[4] : "application/octet-stream";

    HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    HttpRequest r = HttpRequest.newBuilder(URI.create(uploadUrl + "?name=" + name))
        .header("Authorization", "Bearer " + token)
        .header("Accept", "application/vnd.github+json")
        .header("Content-Type", type)
        .header("User-Agent", "linksi-build")
        .POST(HttpRequest.BodyPublishers.ofFile(Path.of(file)))
        .build();
    HttpResponse<String> resp = c.send(r, HttpResponse.BodyHandlers.ofString());
    System.out.println("STATUS=" + resp.statusCode() + "  " + name);
    if (resp.statusCode() >= 300) System.out.println(resp.body());
  }
}

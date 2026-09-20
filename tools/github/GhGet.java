import java.net.URI; import java.net.http.*;
public class GhGet {
  public static void main(String[] a) throws Exception {
    // GitHub answers the job-logs endpoint with a 302 to a pre-signed blob URL. Without following
    // redirects GhGet printed only "STATUS=302" and the log itself was unreachable.
    HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    HttpRequest r = HttpRequest.newBuilder(URI.create(a[0]))
        .header("Authorization", "Bearer " + a[1])
        .header("Accept", "application/vnd.github+json")
        .header("User-Agent", "linksi-build").build();
    HttpResponse<String> resp = c.send(r, HttpResponse.BodyHandlers.ofString());
    System.out.println("STATUS=" + resp.statusCode());
    System.out.println(resp.body());
  }
}
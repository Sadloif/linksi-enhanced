import java.net.URI; import java.net.http.*;
public class GhGet {
  public static void main(String[] a) throws Exception {
    HttpClient c = HttpClient.newHttpClient();
    HttpRequest r = HttpRequest.newBuilder(URI.create(a[0]))
        .header("Authorization", "Bearer " + a[1])
        .header("Accept", "application/vnd.github+json")
        .header("User-Agent", "linksi-build").build();
    HttpResponse<String> resp = c.send(r, HttpResponse.BodyHandlers.ofString());
    System.out.println("STATUS=" + resp.statusCode());
    System.out.println(resp.body());
  }
}
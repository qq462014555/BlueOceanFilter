import com.microsoft.playwright.*;

public class InspectSxkc {
    public static void main(String[] args) {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().connectOverCDP("http://127.0.0.1:9224");

            BrowserContext context = browser.contexts().isEmpty()
                    ? browser.newContext()
                    : browser.contexts().get(0);

            // Try to find existing tab with sxkc.wusetech.com
            Page page = null;
            for (Page p : context.pages()) {
                try {
                    String url = p.url();
                    if (url != null && url.contains("sxkc.wusetech.com")) {
                        page = p;
                        System.out.println("找到已存在页面: " + url);
                        break;
                    }
                } catch (Exception e) {
                    // ignore
                }
            }

            // If no existing tab, create new one
            if (page == null) {
                System.out.println("未找到已存在页面，创建新标签页...");
                page = context.newPage();
            }

            page.navigate("https://sxkc.wusetech.com/administrator-list");
            System.out.println("页面标题: " + page.title());
            System.out.println("当前URL: " + page.url());

            Thread.sleep(3000);

            // Dump all shop links with full href
            System.out.println("\n--- 店铺超链接 (href) ---");
            Object shopLinks = page.evaluate("() => {\n" +
                    "  const links = document.querySelectorAll('.jump-link');\n" +
                    "  return Array.from(links).map(a => ({\n" +
                    "    text: a.textContent.trim(),\n" +
                    "    href: a.getAttribute('href'),\n" +
                    "    dataHref: a.getAttribute('data-href'),\n" +
                    "    onclick: a.getAttribute('onclick'),\n" +
                    "    allAttrs: Array.from(a.attributes).map(attr => attr.name + '=' + attr.value.substring(0,80))\n" +
                    "  }));\n" +
                    "}");
            System.out.println(shopLinks);

            // Also check if clicking the link adds something to URL or localStorage
            System.out.println("\n--- 点击第一个链接前的 URL 和 localStorage ---");
            System.out.println("当前URL: " + page.url());

            Object beforeState = page.evaluate("() => ({\n" +
                    "  url: window.location.href,\n" +
                    "  localStorage: Object.keys(localStorage).map(k => k + '=' + localStorage.getItem(k).substring(0,100))\n" +
                    "})");
            System.out.println(beforeState);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

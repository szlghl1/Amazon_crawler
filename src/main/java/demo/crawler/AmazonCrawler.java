package demo.crawler;

import demo.ad.Ad;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.StopFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.util.Attribute;
import org.apache.lucene.util.AttributeFactory;
import org.apache.lucene.util.AttributeImpl;
import org.apache.lucene.util.Version;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.print.Doc;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * Created by Ling on 5/7/17.
 */
public class AmazonCrawler {
    private HashSet<String> crawledUrl = new HashSet<>();
    private final String proxyUser = "bittiger";
    private final String proxyPassword = "cs504";
    private List<String> proxyList;
    private int indexForProxyList = 0;
    private static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_11_3) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/55.0.2883.95 Safari/537.36";
    private Logger logger = LoggerFactory.getLogger(AmazonCrawler.class);
    private List<String> titleSelectorList;
    private List<String> categorySelectorList;

    public AmazonCrawler(String proxyFile) {
        initHtmlSelector();
        initProxyList(proxyFile);
    }

    //raw url: https://www.amazon.com/KNEX-Model-Building-Set-Engineering/dp/B00HROBJXY/ref=sr_1_14/132-5596910-9772831?ie=UTF8&qid=1493512593&sr=8-14&keywords=building+toys
    //normalizedUrl: https://www.amazon.com/KNEX-Model-Building-Set-Engineering/dp/B00HROBJXY
    private String normalizeUrl(String url) {
        int i = url.indexOf("ref");
        return i == -1 ? url : url.substring(0, i - 1);
    }

    private void initHtmlSelector() {
        titleSelectorList = new ArrayList<>();
        titleSelectorList.add(" > div > div > div > div.a-fixed-left-grid-col.a-col-right > div.a-row.a-spacing-small > div:nth-child(1)  > a > h2");
        titleSelectorList.add(" > div > div > div > div.a-fixed-left-grid-col.a-col-right > div.a-row.a-spacing-small > a > h2");

        categorySelectorList = new ArrayList<>();
        categorySelectorList.add("#refinements > div.categoryRefinementsSection > ul.forExpando > li > a > span.boldRefinementLink");
        categorySelectorList.add("#refinements > div.categoryRefinementsSection > ul.forExpando > li:nth-child(1) > a > span.boldRefinementLink");
    }

    private void initProxyList(String proxyFile) {
        proxyList = new ArrayList<>();
        try(BufferedReader br = new BufferedReader(new FileReader(proxyFile))) {
            String line;
            while((line = br.readLine()) != null) {
                String[] fields = line.split(",");
                String ip = fields[0].trim();
                proxyList.add(ip);
            }
        } catch(IOException e) {
            e.printStackTrace();
        }
        Authenticator.setDefault(
                new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(proxyUser, proxyPassword.toCharArray());
                    }
                }
        );

        System.setProperty("http.proxyUser", proxyUser);
        System.setProperty("http.proxyPassword", proxyPassword);
        System.setProperty("socksProxyPort", "61336");
    }

    private void changeProxy() {
        indexForProxyList = (indexForProxyList + 1) % proxyList.size();
        String proxy = proxyList.get(indexForProxyList);
        System.setProperty("socksProxyHost", proxy);
    }

    private String testProxy() {
        String test_url = "http://www.toolsvoid.com/what-is-my-ip-address";
        try {
            Document doc = Jsoup.connect(test_url).userAgent(USER_AGENT).timeout(10000).get();
            String ip = doc.select("body > section.articles-section > div > div > div > div.col-md-8.display-flex > div > div.table-responsive > table > tbody > tr:nth-child(1) > td:nth-child(2) > strong").first().text(); //get used IP.
            logger.info("current tested IP-Address: " + ip);
            return ip;
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
            return null;
        }
    }

    private String getQueryUrl(String query, int page) {
        final String AMAZON_QUERY_URL = "https://www.amazon.com/s/ref=nb_sb_noss?field-keywords=";
        String pagePara = (page == 1) ? "" : "&page=" + page;
        return AMAZON_QUERY_URL + query + pagePara;
    }

    //key words = cleaned title
    private List<String> cleanAndTokenize(String str) throws IOException {
        if(str == null) return null;

        List<String> tokens = new ArrayList<>();

        //tokenize
        AttributeFactory attributeFactory = AttributeFactory.DEFAULT_ATTRIBUTE_FACTORY;
        StandardTokenizer standardTokenizer = new StandardTokenizer(attributeFactory);
        standardTokenizer.setReader(new StringReader(str));
        standardTokenizer.reset();

        //filter stop word
        CharArraySet stopCharArraySet = CharArraySet.copy(StandardAnalyzer.STOP_WORDS_SET);
        StopFilter stopFilter = new StopFilter(standardTokenizer, stopCharArraySet);

        //to lower case
        LowerCaseFilter lowerCaseFilter = new LowerCaseFilter(stopFilter);

        while(lowerCaseFilter.incrementToken()) {
            tokens.add(lowerCaseFilter.getAttribute(CharTermAttribute.class).toString());
        }

        return tokens;
    }

    private String cleanString(String str) throws IOException {
        String res = new String();
        List<String> tokens = cleanAndTokenize(str);
        for(String token : tokens) {
            res += (token + ' ');
        }
        return res.trim();
    }

    public List<Ad> getAdBasicInfoByQuery(String query, double bidPrice, int campaignId, int queryGroupId) {
        List<Ad> adList = new ArrayList<>();
        try {
            changeProxy();

            int curGoodIndex = 0;
            final int numOfPageToQuery = 10;

            for(int curPage = 1; curPage <= numOfPageToQuery; curPage++) {
                logger.info("getting page: " + curPage);
                String url = getQueryUrl(query, curPage);
                HashMap<String,String> headers = new HashMap<String,String>();
                headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8");
                headers.put("Accept-Encoding", "gzip, deflate, sdch, br");
                headers.put("Accept-Language", "en-US,en;q=0.8");
                Document doc = Jsoup.connect(url).headers(headers).userAgent(USER_AGENT).timeout(100000).get();

                Elements results = doc.select("li[data-asin]");

                //logger.info("num of results = " + results.size());
                for(int offset = 0; offset < results.size(); offset++, curGoodIndex++) {
                    Ad ad = new Ad();

                    ad.detail_url = getDetailUrlFromDoc(doc, curGoodIndex);
                    if(ad.detail_url.isEmpty()) {
                        //cannot parse or queried
                        logger.error("url: " + url);
                        continue;
                    }

                    ad.query = cleanString(query);
                    ad.query_group_id = queryGroupId;

                    ad.title = getTitleFromDoc(doc, curGoodIndex);
                    if (ad.title.isEmpty()) {
                        //cannot parse
                        logger.error("url: " + url);
                        continue;
                    } else {
                        ad.keyWords = cleanAndTokenize(ad.title);
                    }

                    ad.thumbnail = getThumbnailFromDoc(doc, curGoodIndex);
                    if(ad.thumbnail.isEmpty()) {
                        logger.error("url: " + url);
                        continue;
                    }

                    ad.brand = getBrandFromDoc(doc, curGoodIndex);
                    ad.brand = ad.brand == null ? "" : ad.brand;

                    ad.bidPrice = bidPrice;
                    ad.campaignId = campaignId;

                    ad.price = getPriceFromDoc(doc, curGoodIndex);

                    ad.category = getCategoryFromDoc(doc, curGoodIndex);

                    adList.add(ad);
                }
            }
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        return adList;
    }

    private String getDetailUrlFromDoc(Document document, int itemNum) {
        //detail url
        String detail_path = "#result_"+ Integer.toString(itemNum)+ " > div > div > div > div.a-fixed-left-grid-col.a-col-left > div > div > a";
        Element detail_url_ele = document.select(detail_path).first();
        if(detail_url_ele != null) {
            String detail_url = detail_url_ele.attr("href");
            String normalizedUrl = normalizeUrl(detail_url);
            if(crawledUrl.contains(normalizedUrl)) {
                logger.info("skipped crawled detail url:" + normalizedUrl);
                return "";
            }
            crawledUrl.add(normalizedUrl);
            return normalizedUrl;
        } else {
            logger.error("failed to parse detail");
            return "";
        }
    }

    private String getTitleFromDoc(Document document, int itemNum) {
        //#result_2 > div > div > div > div.a-fixed-left-grid-col.a-col-right > div.a-row.a-spacing-small > div:nth-child(1) > a > h2
        //#result_3 > div > div > div > div.a-fixed-left-grid-col.a-col-right > div.a-row.a-spacing-small > div:nth-child(1) > a > h2
        //#result_0 > div > div > div > div.a-fixed-left-grid-col.a-col-right > div.a-row.a-spacing-small > div:nth-child(1) > a > h2
        //#result_1 > div > div > div > div.a-fixed-left-grid-col.a-col-right > div.a-row.a-spacing-small > div:nth-child(1) > a > h2
        for (String title : titleSelectorList) {
            String title_ele_path = "#result_"+Integer.toString(itemNum)+ title;
            Element title_ele = document.select(title_ele_path).first();
            if(title_ele != null) {
                //System.out.println("title = " + title_ele.text());
                return title_ele.text();
            }
        }
        logger.error("failed to cannot parse title");
        return "";
    }

    private String getThumbnailFromDoc(Document document, int itemNum) {
        String thumbnail_path = "#result_"+Integer.toString(itemNum)+" > div > div > div > div.a-fixed-left-grid-col.a-col-left > div > div > a > img";
        Element thumbnail_ele = document.select(thumbnail_path).first();
        if(thumbnail_ele != null) {
            //System.out.println("thumbnail = " + thumbnail_ele.attr("src"));
            return thumbnail_ele.attr("src");
        } else {
            logger.error("failed to parse thumbnail");
            return "";
        }
    }

    private String getBrandFromDoc(Document document, int itemNum) {
        String brand_path = "#result_"+Integer.toString(itemNum)+" > div > div > div > div.a-fixed-left-grid-col.a-col-right > div.a-row.a-spacing-small > div > span:nth-child(2)";
        Element brand = document.select(brand_path).first();
        if(brand != null) {
            //System.out.println("brand = " + brand.text());
            return brand.text();
        } else {
            logger.error("failed to parse brand");
            return "";
        }
    }

    private Double getPriceFromDoc(Document document, int itemNum) {
        //price
        double price = 0;
        String price_whole_path = "#result_" + Integer.toString(itemNum) + " > div > div > div > div.a-fixed-left-grid-col.a-col-right > div:nth-child(3) > div.a-column.a-span7 > div:nth-child(1) > div:nth-child(3) > a > span > span > span";
        String price_fraction_path = "#result_" + Integer.toString(itemNum) + " > div > div > div > div.a-fixed-left-grid-col.a-col-right > div:nth-child(2) > div.a-column.a-span7 > div.a-row.a-spacing-none > a > span > span > sup.sx-price-fractional";
        Element price_whole_ele = document.select(price_whole_path).first();
        Element price_fraction_ele = document.select(price_fraction_path).first();
        if(price_whole_ele != null) {
            String price_whole = price_whole_ele.text();
            //System.out.println("price whole = " + price_whole);
            //remove ","
            //1,000
            price_whole = price_whole.replaceAll(",","");
            try {
                price = Double.parseDouble(price_whole);
                if(price_fraction_ele != null) {
                    price += Double.parseDouble(price_fraction_ele.text()) / 100.0;
                }
            } catch (NumberFormatException ne) {
                // TODO Auto-generated catch block
                ne.printStackTrace();
                logger.error("caught error in parsing price. price_whole ele = " + price_whole);
            }
            return price;
        } else {
            logger.error("failed to get price ele");
            return 0.0;
        }
    }

    private String getCategoryFromDoc(Document document, int itemNum) {
        //System.out.println("price = " + ad.price );

        //category
        for (String category : categorySelectorList) {
            Element category_ele = document.select(category).first();
            if(category_ele != null) {
                //System.out.println("category = " + category_ele.text());
                return category_ele.text();
            }
        }
        logger.error("failed to parse category");
        return "";
    }
}

package main;

import jdk.nashorn.internal.runtime.options.Option;

import java.net.*;
import java.io.*;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main {
    public static void main(String[] args) {
        try {
            readJsonFromUrl("https://jsonmock.hackerrank.com/api/medical_records",1);
        }catch(Exception ex){
            ex.printStackTrace();
        }
    }

    private static String readAll(Reader rd) throws IOException {
        StringBuilder sb = new StringBuilder();
        int cp;
        while ((cp = rd.read()) != -1) {
            sb.append((char) cp);
        }
        return sb.toString();
    }

    private static Optional<String> getValue(String key, String jsonText){
        Matcher matcher = Pattern.compile("\"" + key + "\":(\\d+)").matcher(jsonText);
        Optional<String> ret = Optional.empty();
        while(matcher.find()){
            ret = Optional.ofNullable(matcher.group());
        }
        return ret;
    }

    private static Function<String,Pattern> arrayPattern = (String key) -> Pattern.compile("(?:\""+key+"\":)(.*)");
    //private Function<String,Pattern> objectPattern = (String key) -> Pattern.compile("\"" + key + "\":(\\{)\b+(\\})");
    private static Function<String,Pattern> intValuePattern = (String key) -> Pattern.compile("\"" + key + "\":(\\d+)");
    private static Function<String,Pattern> stringValuePattern = (String key) -> Pattern.compile("\"" + key + "\":\"([^\"]+)\"");

    private static Optional<String> getField(Function<String,Pattern> pattern,String key, String jsonText){
        Matcher matcher = pattern.apply(key).matcher(jsonText);
        Optional<String> ret = Optional.empty();
        while(matcher.find()){
            ret = Optional.ofNullable(matcher.group());
            break;
        }
        return ret;
    }



    public static List<Integer> readJsonFromUrl(String url,int page) throws IOException {
        InputStream is = new URL(url+"?&page="+page).openStream();
        try {
            BufferedReader rd = new BufferedReader(new InputStreamReader(is, Charset.forName("UTF-8")));
            String jsonText = readAll(rd);

            System.out.println(jsonText);

            return Collections.emptyList();
        } finally {
            is.close();
        }
    }
}

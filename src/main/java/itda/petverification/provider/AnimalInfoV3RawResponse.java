package itda.petverification.provider;

public record AnimalInfoV3RawResponse(Response response) {
    public record Response(Header header, Body body) { }
    public record Header(String resultCode, String resultMsg, String errorMsg) { }
    public record Body(Item item) { }
    public record Item(
            String dogRegNo,
            String rfidGubun,
            String dogNm,
            String birthDt,
            String sexNm,
            String kindNm,
            String neuterYn,
            String aprGbNm
    ) { }
}

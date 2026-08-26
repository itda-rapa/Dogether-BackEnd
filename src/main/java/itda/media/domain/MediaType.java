package itda.media.domain;

public enum MediaType {
    IMAGE,
    VIDEO;

    public String fileExtension(){
        return fileExtension(contentType());
    }

    public String fileExtension(String contentType) {
        if (this == IMAGE) {
            return switch (contentType) {
                case "image/png" -> ".png";
                case "image/webp" -> ".webp";
                default -> ".jpg";
            };
        }
        return ".mp4";
    }
    public String contentType(){
        return switch (this){
            case IMAGE -> "image/jpeg";
            case VIDEO -> "video/mp4";
        };
    }
}

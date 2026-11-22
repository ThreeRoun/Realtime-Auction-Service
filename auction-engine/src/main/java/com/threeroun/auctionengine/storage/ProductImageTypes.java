package com.threeroun.auctionengine.storage;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

// 업로드 허용 이미지 형식과 저장 파일명 규칙을 한곳에 모은다 - 업로드(ProductController)와
// 조회(ProductImageController)가 같은 규칙을 써야 하기 때문이다.
public final class ProductImageTypes {

    // 확장자는 클라이언트가 보낸 원본 파일명을 그대로 믿지 않고(경로 조작 방지) content-type으로부터 직접 매핑해서 정한다.
    private static final Map<String, String> EXTENSION_BY_CONTENT_TYPE = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp");

    private static final Map<String, String> CONTENT_TYPE_BY_EXTENSION = Map.of(
            "jpg", "image/jpeg",
            "png", "image/png",
            "webp", "image/webp");

    // ProductImageService가 만드는 파일명("<UUID>.<확장자>")만 허용한다. 조회 경로(/images/{filename})로
    // "../" 같은 값이 들어와 저장소 밖 파일이나 다른 오브젝트 키에 접근하는 것을 막는다.
    private static final Pattern FILENAME = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png|webp)$");

    private ProductImageTypes() {
    }

    public static Optional<String> extensionFor(String contentType) {
        return Optional.ofNullable(contentType).map(EXTENSION_BY_CONTENT_TYPE::get);
    }

    public static boolean isValidFilename(String filename) {
        return filename != null && FILENAME.matcher(filename).matches();
    }

    // isValidFilename()을 통과한 파일명에만 호출한다
    public static String contentTypeFor(String filename) {
        String extension = filename.substring(filename.lastIndexOf('.') + 1);
        return CONTENT_TYPE_BY_EXTENSION.get(extension);
    }
}

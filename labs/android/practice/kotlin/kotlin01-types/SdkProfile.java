package tw.harry.labs.practice.kotlin01types;

/**
 * 模擬第三方 SDK 的會員資料（Java 寫的，我們改不了）。
 * 沒有任何 @Nullable／@NonNull 標註，所以 Kotlin 看不出 getNickname() 會不會回傳 null。
 */
public class SdkProfile {
    private final String nickname;

    public SdkProfile(String nickname) {
        this.nickname = nickname;
    }

    /** 使用者還沒設定暱稱時回傳 null */
    public String getNickname() {
        return nickname;
    }
}

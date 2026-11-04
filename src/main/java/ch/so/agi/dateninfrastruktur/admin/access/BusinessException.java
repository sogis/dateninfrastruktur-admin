package ch.so.agi.dateninfrastruktur.admin.access;
public final class BusinessException extends RuntimeException {
    private final int status;
    private final String code;
    public BusinessException(int status,String code,String message){super(message);this.status=status;this.code=code;}
    public int status(){return status;}
    public String code(){return code;}
    public static BusinessException forbidden(){return new BusinessException(403,"FORBIDDEN","Für diesen Vorgang sind Sie nicht zuständig.");}
    public static BusinessException conflict(String message){return new BusinessException(409,"CONFLICT",message);}
    public static BusinessException invalid(String message){return new BusinessException(400,"INVALID_INPUT",message);}
    public static BusinessException missing(){return new BusinessException(404,"NOT_FOUND","Der Eintrag wurde nicht gefunden.");}
}

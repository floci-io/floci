public class FileSystemSize {

    private Long timestamp;
    private Long value;
    private Long valueInArchive;
    private Long valueInIA;
    private Long valueInStandard;

    public Long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
    }

    public Long getValue() {
        return value;
    }

    public void setValue(Long value) {
        this.value = value;
    }

    public Long getValueInArchive() {
        return valueInArchive;
    }

    public void setValueInArchive(Long valueInArchive) {
        this.valueInArchive = valueInArchive;
    }

    public Long getValueInIA() {
        return valueInIA;
    }

    public void setValueInIA(Long valueInIA) {
        this.valueInIA = valueInIA;
    }

    public Long getValueInStandard() {
        return valueInStandard;
    }

    public void setValueInStandard(Long valueInStandard) {
        this.valueInStandard = valueInStandard;
    }
}
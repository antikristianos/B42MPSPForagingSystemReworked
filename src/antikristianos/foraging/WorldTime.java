package antikristianos.foraging;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.Objects;

/** World hours drive regeneration; calendar dates select the monthly capacity. */
public record WorldTime(double hours, LocalDateTime calendar) {
    public WorldTime {
        if(!Double.isFinite(hours)||hours<0) throw new IllegalArgumentException("invalid world hours");
        Objects.requireNonNull(calendar,"calendar");
    }
    public WorldTime plusHours(double amount) {
        if(!Double.isFinite(amount)||amount<0) throw new IllegalArgumentException("invalid elapsed time");
        return new WorldTime(hours+amount,calendar.plusNanos(Math.round(amount*3_600_000_000_000d)));
    }
    public double untilMonthEnd() {
        return Duration.between(calendar,calendar.toLocalDate().withDayOfMonth(1).plusMonths(1).atStartOfDay()).toNanos()/3_600_000_000_000d;
    }
}

package com.dragon.read.user.model;

/**
 * COMPILE-TIME STUB ONLY -- never dexed. See
 * {@link com.dragon.read.component.biz.impl.privilege.PrivilegeManager}.
 *
 * <p>Time fields are SECONDS. That is not an assumption:
 * {@code a54.g.getCommentForbiddenLeftDays()} computes
 * {@code (privilege.getExpireTime() * 1000) - System.currentTimeMillis()},
 * so {@code expire_time} is epoch seconds and {@code left_time} is a duration
 * in seconds -- {@code leftSecondsAfterInit} multiplies by 1000 the same way.
 */
public class PrivilegeInfoModel {

    public void setId(String id) {
    }

    public void setName(String name) {
    }

    public void setIsForever(int value) {
    }

    public void setLeftTime(long seconds) {
    }

    public void setExpireTime(long epochSeconds) {
    }

    public void setRemainTimes(Long times) {
    }

    public long getExpireTime() {
        return 0L;
    }
}

package org.nustaq.reallive.newimpl;

import java.util.Arrays;

/**
 * Created by ruedi on 03/08/15.
 *
 */
public class Diff {

    final String changedFields[];
    final Object oldValues[];

    public Diff(String[] changedFields, Object[] oldValues) {
        this.changedFields = changedFields;
        this.oldValues = oldValues;
    }

    public String[] getChangedFields() {
        return changedFields;
    }

    public Object[] getOldValues() {
        return oldValues;
    }

    @Override
    public String toString() {
        return "Diff{" +
                "changedFields=" + Arrays.toString(changedFields) +
                ", oldValues=" + Arrays.toString(oldValues) +
                '}';
    }
}

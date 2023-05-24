/*
 * This file is part of Haveno.
 *
 * Haveno is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Haveno is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Haveno. If not, see <http://www.gnu.org/licenses/>.
 */

package haveno.common.proto.persistable;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

public abstract class PersistableList<T extends PersistablePayload> implements PersistableEnvelope {

    @Getter
    public final List<T> list = createList();

    protected List<T> createList() {
        return new ArrayList<>();
    }

    public PersistableList() {
    }

    protected PersistableList(Collection<T> collection) {
        setAll(collection);
    }

    public void setAll(Collection<T> collection) {
        synchronized (this.list) {
            this.list.clear();
            this.list.addAll(collection);
        }
    }

    public boolean add(T item) {
        synchronized (list) {
            if (!list.contains(item)) {
                list.add(item);
                return true;
            }
            return false;
        }
    }

    public boolean remove(T item) {
        synchronized (list) {
            return list.remove(item);
        }
    }

    public Stream<T> stream() {
        synchronized (list) {
            return list.stream();
        }
    }

    public int size() {
        synchronized (list) {
            return list.size();
        }
    }

    public boolean contains(T item) {
        synchronized (list) {
            return list.contains(item);
        }
    }

    public boolean isEmpty() {
        synchronized (list) {
            return list.isEmpty();
        }
    }

    public void forEach(Consumer<? super T> action) {
        synchronized (list) {
            list.forEach(action);
        }
    }

    public void clear() {
        synchronized (list) {
            list.clear();
        }
    }
}

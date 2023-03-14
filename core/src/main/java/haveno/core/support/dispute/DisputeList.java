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

package haveno.core.support.dispute;

import haveno.common.proto.persistable.PersistableListAsObservable;
import haveno.common.proto.persistable.PersistablePayload;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;

@Slf4j
@ToString
/*
 * Holds a List of Dispute objects.
 *
 * Calls to the List are delegated because this class intercepts the add/remove calls so changes
 * can be saved to disc.
 */
public abstract class DisputeList<T extends PersistablePayload> extends PersistableListAsObservable<T> {

    public DisputeList() {
    }

    protected DisputeList(Collection<T> collection) {
        super(collection);
    }
}

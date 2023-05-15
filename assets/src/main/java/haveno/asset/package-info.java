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

/**
 * Haveno's family of abstractions representing different ("crypto")
 * {@link haveno.asset.Asset} types such as {@link haveno.asset.Coin},
 * {@link haveno.asset.Token} and {@link haveno.asset.Erc20Token}, as well as concrete
 * implementations of each, such as {@link haveno.asset.coins.Bitcoin} itself, cryptos like
 * {@link haveno.asset.coins.Litecoin} and {@link haveno.asset.coins.Ether} and tokens like
 * {@link haveno.asset.tokens.DaiStablecoin}.
 * <p>
 * The purpose of this package is to provide everything necessary for registering
 * ("listing") new assets and managing / accessing those assets within, e.g. the Haveno
 * Desktop UI.
 * <p>
 * Note that everything within this package is intentionally designed to be simple and
 * low-level with no dependencies on any other Haveno packages or components.
 *
 * @author Chris Beams
 * @since 0.7.0
 */

package haveno.asset;

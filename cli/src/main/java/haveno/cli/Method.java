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

package haveno.cli;

/**
 * Currently supported api methods.
 */
public enum Method {
    // account
    accountexists,
    backupaccount,
    changepassword,
    closeaccount,
    createaccount,
    deleteaccount,
    isaccountopen,
    isappinitialized,
    openaccount,
    restoreaccount,
    // wallets
    createxmrsweeptxs,
    createxmrtx,
    getaddressbalance,
    getbalance,
    getfundingaddresses,
    getwalletheight,
    getxmrnewsubaddress,
    getxmrprimaryaddress,
    getxmrseed,
    getxmrtxs,
    lockwallet,
    relayxmrtxs,
    removewalletpassword,
    sendxmr,
    setwalletpassword,
    unlockwallet,
    // prices
    getmarketdepth,
    getxmrprice,
    getxmrprices,
    // offers
    activateoffer,
    canceloffer,
    createoffer,
    deactivateoffer,
    editoffer,
    @Deprecated // Since 27-Dec-2021.
    getmyoffer, // Endpoint to be removed from future version.  Use getoffer instead.
    getmyoffers,
    getoffer,
    getoffers,
    // trades
    completetrade,
    confirmpaymentreceived,
    confirmpaymentsent,
    getchatmessages,
    gettrade,
    gettrades,
    sendchatmessage,
    takeoffer,
    withdrawfunds,
    // payment accounts
    createcryptopaymentacct,
    createpaymentacct,
    deletepaymentacct,
    getcryptopaymentmethods,
    getpaymentacctform,
    getpaymentaccts,
    getpaymentmethods,
    // disputes
    getdispute,
    getdisputes,
    opendispute,
    resolvedispute,
    senddisputechatmessage,
    // dispute agents
    registerdisputeagent,
    unregisterdisputeagent,
    // trade statistics
    gettradestatistics,
    // xmr connections
    addconnection,
    checkconnection,
    getautoswitch,
    getbestconnection,
    getconnection,
    getconnections,
    removeconnection,
    setautoswitch,
    setconnection,
    // xmr node
    getxmrnodesettings,
    isxmrnodeonline,
    startxmrnode,
    stopxmrnode,
    // notifications
    registernotificationlistener,
    // server
    getversion,
    stop
}

package com.gumbocoin.server

import discord4j.common.json.EmojiResponse
import discord4j.core.DiscordClient
import discord4j.core.`object`.util.Snowflake
import io.rsocket.Payload
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.toFlux
import systems.carson.base.*
import java.nio.charset.Charset
import java.time.Duration
import java.time.Instant


val handlerLogger = GLogger.logger("Handler")
enum class StreamHandler(
    val request :Request.Stream,
    val handler :(RequestDataBlob) -> Flux<Payload>){
    NUMBERS(Request.Stream.NUMBERS,req@{pay ->
        pay as IntDataBlob
        return@req Flux.range(0,pay.value).map { "" + it }.map { it.toPayload() }
    }),
    BLOCKCHAIN_UPDATES(Request.Stream.BLOCKCHAIN_UPDATES, req@ {
//        println("dataCache: ${serialize(dataCache)}")
        val value = ActionUpdate(dataCache, blockchain.blocks.last().hash,diff)
//        println("Value: ${serialize(value)}")
        updateSource.toFlux().startWith(value)
            .map { println("Sending out ${serialize(it)}");it }
            .map { it.toPayload() }
//        Flux.just(value)
//            .map { println("Sending out ${serialize(it)}");it }
//            .map { it.toPayload() }
    })
}

enum class ResponseHandler(
    val request :Request.Response,
    val handler :(RequestDataBlob) -> Payload){

    PING(Request.Response.PING,req@ { "pong".toPayload() }),
    DECRYPT(Request.Response.DECRYPT,req@ {pay ->
        pay as EncryptedDataBlob
        val plain = KeyManager.server.decryptAES(pay.data.toBytes())
        "${pay.clientID}:\"${plain.toString(Charset.forName("UTF-8"))}\"".toPayload()
    }),
    SIGN_UP(Request.Response.SIGN_UP,req@ {pay ->
        pay as SignUpDataBlob

        if (blockchain.users.any { it.id == pay.clientID })
            return@req Status(failed = true, errorMessage = "User already exists").toPayload()
        addToDataCache(pay.signUpAction)
        Status().toPayload()
    }),
    VERIFIED(Request.Response.VERIFIED,req@ {pay ->
        return@req SendableBoolean(pay.isVerified).toPayload()
    }),
    BLOCK(Request.Response.BLOCK, req@ { pay ->
        pay as BlockDataBlob
        val block:Block = pay.block
        handlerLogger.debug("got block:$block")

        if(block.difficulty != diff){
            return@req Status(failed = true,
                errorMessage = "Difficulty is wrong",
                extraData = "Expected $diff, got ${block.difficulty}").toPayload()
        }

        if(!block.hash.isValid())
            return@req Status(failed = true, errorMessage = "Block hash is wrong",extraData = "Hash: ${block.hash}").toPayload()
        if(dataCache != pay.block.actions){
            return@req Status(
                failed = true,
                errorMessage = "Actions are invalid",
                extraData = "Expected :$dataCache, got ${pay.block.actions}"
            ).toPayload()
        }
        if(block.lasthash != blockchain.blocks.last().hash)
            return@req Status(failed = true,
                errorMessage = "lasthash is not correct",
                extraData = "expected: ${blockchain.blocks.last().hash} and got ${block.lasthash}").toPayload()
        val newBlockchain = blockchain.newBlock(block)
        val valid = newBlockchain.isValid()

        if(valid.isPresent) {
            return@req Status(
                failed = true,
                errorMessage = "Block is invalid when added to the blockchain",
                extraData = valid.get()
            ).toPayload()
        }


        blockchain = newBlockchain
        makeNewDiff()

        clearDataCache()
        DiscordManager.blockchainChannel
            .flatMap { it.createEmbed { spec ->
                spec.setTitle("Author:" + block.author)
                    .setTimestamp(Instant.ofEpochMilli(block.timestamp))
                    .addField("nonce","" + block.nonce,true)
                    .addField("difficulty","" + block.difficulty,true)
                    .addField("lasthash",block.lasthash,true)
                    .addField("hash",block.hash,true)
                block.actions.forEach { act ->
                    when(act){
                        is SignUpAction -> {
                            spec.addField("Signed up",act.clientID,true)
                        }
                        is DataAction -> {
                            spec.addField("Data for ${act.clientID} (${act.data.uniqueID})",act.data.key + " : " + act.data.value,true)
                        }
                        is TransactionAction -> {
//                            println("===")
//                            val gbc = DiscordManager.client
//                                .getGuildEmojiById(Snowflake.of(566998443218960434),Snowflake.of(572065175818076200))
//                                .map { w -> w.asFormat() }
//                                .block()
//                            println("===")
                            val s = " Gumbocoin"//if(true) " Gumbocoins " else "<:gbc:572065175818076200:>"
                            spec.addField("`${act.clientID}` paid `${act.recipientID}`","${act.amount} $s",true)
                        }
                    }
                }
                spec.setAuthor("Block ${blockchain.blocks.size - 1}","","")
            } }
            .subscribe()
        Status().toPayload()
    }),
    BLOCKCHAIN(Request.Response.BLOCKCHAIN, { blockchain.toPayload() }),
    TRANSACTION(Request.Response.TRANSACTION, req@ {pay ->
        if(!pay.isVerified)
            return@req Status(failed = true,errorMessage =  "Unverified RequestDataBlob").toPayload()

        pay as TransactionDataBlob

        if (!blockchain.users.any { it.id == pay.clientID })
            return@req Status(failed = true, errorMessage = "User does not exists").toPayload()

        if(pay.clientID != pay.transactionAction.clientID)
            return@req Status(failed = true, errorMessage = "Different clientID for the DataBlob object and the transaction object object",
                extraData = pay.clientID + " verses " + pay.transactionAction.clientID).toPayload()


        val user = blockchain.users.first { it.id == pay.clientID }

        if(!pay.transactionAction.isSignatureValid(user.person.publicKey))
            return@req Status(failed = true, errorMessage = "Signature on transactionAction is not valid").toPayload()

        if(blockchain.amounts[pay.transactionAction.clientID] ?: -1 < pay.transactionAction.amount)
            return@req Status(failed = true, errorMessage = "You have insufficient funds",
                extraData = "You have ${blockchain.amounts[pay.transactionAction.clientID]}gc, you need ${pay.transactionAction.amount}").toPayload()

        addToDataCache(pay.transactionAction)
        Status().toPayload()
    }),
    MONEY(Request.Response.MONEY, { pay ->
        pay as StringDataBlob
        SendableInt(blockchain.amounts[pay.value] ?: 0).toPayload()
    }),
    DATA_SUBMISSION(Request.Response.DATA, req@ {pay ->

        if(!pay.isVerified)
            return@req Status(failed = true,errorMessage =  "Unverified RequestDataBlob").toPayload()

        pay as DataSubmissionDataBlob

        if(pay.action.clientID != pay.clientID)
            return@req Status(failed = true, errorMessage = "Different clientID for the DataBlob object and the transaction object object",
                extraData = pay.clientID + " verses " + pay.action.clientID).toPayload()

        if(pay.action.data.key != "name")
            return@req Status(failed = true, errorMessage = "Data without the key `name` is invalid").toPayload()
//        TODO("Remove, duh")

        val user = blockchain.users.first { it.id == pay.clientID }

        if(!pay.action.isSignatureValid(user.person.publicKey))
            return@req Status(failed = true, errorMessage = "Signature on dataAction is not valid").toPayload()

        if(blockchain.blocks.flatMap { it.actions }
                .filter { it.type == ActionType.DATA }
                .map { it as DataAction}
                .any { it.data.uniqueID == pay.action.data.uniqueID })
            return@req Status(failed = true, errorMessage = "Data already exists with identical ID",
                extraData = "ID: ${pay.action.data.uniqueID}").toPayload()

        addToDataCache(pay.action)
        Status().toPayload()
    })
}


val targetBlockTime: Duration = Duration.ofMinutes(1)

fun makeNewDiff(){
    //TODO make more concise and make a get() on the diff value.
    // memoized per blockchain?
    val allTimedBlocks = blockchain.blocks.subList(
        blockchain.blocks.indexOfFirst { !Duration.ofMillis(it.timestamp).minusMinutes(10).isNegative },
        blockchain.blocks.size)

    val timedBlocks = if(allTimedBlocks.size > 5) allTimedBlocks.subList(0,5) else allTimedBlocks

    if(timedBlocks.isEmpty() || blockchain.blocks.size < 4){
        //if there are no blocks, either no one is mineing or no one has mined anything in the last hour
        // just don't change diff. It's not worth the time
        return
    }

    val totalTime = Duration.ofMillis(timedBlocks.last().timestamp - timedBlocks.first().timestamp)
    val timePerBlock = totalTime.dividedBy(timedBlocks.size.toLong())
    //what do we want timePerBlock to be? I say 5. Lets do 5
    //edit:changed to 1 min
    val diffOverTime = timedBlocks.map { it.difficulty }.average().toLong()
    var timePerDiff = timePerBlock.toMillis() / diffOverTime
    if(timePerDiff == 0L)
        timePerDiff = 1
    val newDiff = targetBlockTime.dividedBy(timePerDiff).toMillis()
    println("newDiff: $newDiff")
    if(newDiff > 3)
        diff = newDiff

    System.err.println("new diff is $diff")
    // m a t h
    // timePerDiff = timePerBlock / diff
    // timePerDiff * diff = timePerBlock
    // timePerDiff * newDiff = targetBlockTime    // swap out timePerBlock with the target, and diff with the new value
    // newDiff = targetBlockTime / timePerDiff //isolate newDiff

}
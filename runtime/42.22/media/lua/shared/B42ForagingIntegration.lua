-- World-integration gate. Regular Vanilla findings/XP are not intercepted in this candidate.
local moduleName = "B42MPSPForagingSystemReworked"
local searchStates = {}
local ticks = 0
local missingReported = false
local lastSearch = {}
local rulesInstalled = false
local clientReady = false
local LIGHT_PROTOCOL = 2
local LIGHT_REACH_SQUARED = 36
local lastLightPoll = nil

local function sendLightRequest(player, request)
    sendServerCommand(player, moduleName, "light", request)
    print("[B42Foraging] LIGHT_SENT request=" .. request.requestId .. " attempt=" .. tostring(request.attempt))
end

local function bridge()
    if B42ForagingBridge then
        if isClient() and not clientReady then
            clientReady = true
            print("[B42Foraging] LIGHT_CLIENT_READY protocol=" .. LIGHT_PROTOCOL)
        end
        if not isClient() and B42ForagingRules and not rulesInstalled then
            B42ForagingBridge.setSearchRules(B42ForagingRules)
            rulesInstalled = true
        end
        return B42ForagingBridge
    end
    if not missingReported then
        missingReported = true
        print("[B42Foraging] ERROR Java bridge unavailable; check ZombieBuddy approval and Java loading")
    end
    return nil
end

local function onTick()
    ticks = ticks + 1
    local api = bridge()
    if not api then return end
    if not isClient() then api.tick() end
    if isServer() then
        local now = getTimestampMs()
        if not lastLightPoll or now < lastLightPoll or now - lastLightPoll >= 100 then
            lastLightPoll = now
            local players = getOnlinePlayers()
            for index = 0, players:size() - 1 do
                local player = players:get(index)
                local request = api.requestLight(player)
                if request then sendLightRequest(player, request) end
            end
        end
    end
    if ticks % 10 ~= 0 then return end
    if isServer() then if ticks % 120 == 0 then api.beginWorld() end; return end
    if not ISSearchManager then return end
    for index = 0, getNumActivePlayers() - 1 do
        local player = getSpecificPlayer(index)
        if player then
            local manager = ISSearchManager.getManager(player)
            local enabled = manager and manager.isSearchMode == true
            local window = ISSearchWindow and ISSearchWindow.players and ISSearchWindow.players[player]
            local focus = window and window.searchFocusCategory or "None"
            if type(focus) ~= "string" then focus = "None" end
            local previous = searchStates[index]
            if (enabled and ticks % 120 == 0) or not previous or previous.enabled ~= enabled or previous.focus ~= focus then
                searchStates[index] = { enabled = enabled, focus = focus }
                if isClient() then
                    sendClientCommand(player, moduleName, "search", { enabled = enabled, focus = focus })
                elseif enabled then
                    api.setSearchState(player, true, focus)
                    api.observeLocalLight(player)
                else api.cancelSearch(player) end
            end
        end
    end
end

local function onClientCommand(module, command, player, args)
    if module ~= moduleName or not isServer() or not player then return end
    local api = bridge()
    if not api or type(args) ~= "table" then return end
    if command == "search" then
        if args.enabled == true then
            local id = player:getOnlineID()
            local now = getTimestampMs()
            if lastSearch[id] and now - lastSearch[id] < 500 then return end
            lastSearch[id] = now
            api.setSearchState(player, true, type(args.focus) == "string" and args.focus or "None")
            local request = api.requestLight(player)
            if request then sendLightRequest(player, request) end
        else
            lastSearch[player:getOnlineID()] = nil
            api.cancelSearch(player)
        end
    elseif command == "light" then api.acceptLight(player, args) end
end

local function coordinate(value)
    return type(value) == "number" and value == value and math.abs(value) <= 2147483647 and value == math.floor(value)
end

local function token(value)
    return type(value) == "string" and #value > 0 and #value <= 64 and not value:find("%c")
end

local function inReach(x, y, z, targetX, targetY, targetZ)
    local dx, dy = x - targetX, y - targetY
    return z == targetZ and dx * dx + dy * dy <= LIGHT_REACH_SQUARED
end

local function lightReply(player, request, status, value)
    sendClientCommand(player, moduleName, "light", { requestId = request.requestId, session = request.session, status = status, light = value })
    print("[B42Foraging] LIGHT_CLIENT_REPLY request=" .. request.requestId .. " status=" .. status)
end

local function onServerCommand(module, command, args)
    if module ~= moduleName or command ~= "light" or not isClient() or type(args) ~= "table" then return end
    if not token(args.requestId) or not token(args.session) or not coordinate(args.onlineId) then return end
    print("[B42Foraging] LIGHT_RECEIVED request=" .. args.requestId)
    for index = 0, getNumActivePlayers() - 1 do
        local player = getSpecificPlayer(index)
        if player and player:getOnlineID() == args.onlineId then
            if args.protocol ~= LIGHT_PROTOCOL then lightReply(player, args, "unsupported_protocol"); return end
            local targetX, targetY, targetZ = args.targetX, args.targetY, args.targetZ
            if not coordinate(args.x) or not coordinate(args.y) or not coordinate(args.z)
                or not coordinate(targetX) or not coordinate(targetY) or not coordinate(targetZ)
                or not inReach(args.x, args.y, args.z, targetX, targetY, targetZ) then
                lightReply(player, args, "malformed_request"); return
            end
            local manager = ISSearchManager and ISSearchManager.getManager(player)
            if not manager or not manager.isSearchMode then lightReply(player, args, "search_ended"); return end
            local x, y, z = math.floor(player:getX()), math.floor(player:getY()), math.floor(player:getZ())
            if z ~= targetZ then lightReply(player, args, "wrong_floor"); return end
            if not inReach(x, y, z, targetX, targetY, targetZ) then lightReply(player, args, "out_of_range"); return end
            local cell = player:getCell()
            local square = cell and cell:getGridSquare(targetX, targetY, targetZ)
            if not square then lightReply(player, args, "missing_square"); return end
            local value = square:getLightLevel(index)
            if type(value) ~= "number" or value ~= value or value < 0 or value > 1 then lightReply(player, args, "invalid_light"); return end
            local api = bridge()
            if api then print("[B42Foraging] LIGHT_SAMPLE request=" .. args.requestId .. " " .. api.lightDiagnostic(player, square, index)) end
            lightReply(player, args, "ok", value)
            return
        end
    end
    print("[B42Foraging] LIGHT_CLIENT_REJECT request=" .. args.requestId .. " reason=player_unavailable")
end

local function beginWorld()
    local api = bridge()
    if api and not isClient() then api.beginWorld() end
end

Events.OnTick.Add(onTick)
Events.OnGameStart.Add(beginWorld)
Events.OnServerStarted.Add(beginWorld)
Events.OnClientCommand.Add(onClientCommand)
Events.OnServerCommand.Add(onServerCommand)

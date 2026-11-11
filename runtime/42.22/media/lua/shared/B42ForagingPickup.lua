require "Foraging/ISForageAction"

-- Chain only our own find IDs through the native action's existing server lifecycle.
local original = {}
for _, name in ipairs({ "isValid", "isValidStart", "serverStart", "serverStop", "start", "stop", "complete", "perform" }) do original[name] = ISForageAction[name] end
local function own(action) return type(action.iconID) == "string" and action.iconID:sub(1, 5) == "b42f:" end
local function fallback(name, self) if original[name] then return original[name](self) end end
local function localValid(self)
    local icon = self.manager and self.manager.forageIcons[self.iconID]
    if not icon or not icon.b42Record then return false end
    if self.b42Started then return getGameTime():getWorldAgeHours() < icon.b42Record.expires end
    return icon:canPickup()
end

function ISForageAction:isValidStart()
    if not own(self) then return fallback("isValidStart", self) end
    if isClient() then return localValid(self) end
    return B42ForagingBridge and B42ForagingBridge.pickupValid(self.character, self.iconID, self)
end
function ISForageAction:isValid()
    if not own(self) then return fallback("isValid", self) end
    if isClient() then return localValid(self) end
    return B42ForagingBridge and B42ForagingBridge.pickupValid(self.character, self.iconID, self)
end
function ISForageAction:serverStart()
    if not own(self) then return fallback("serverStart", self) end
    self.b42Started = B42ForagingBridge and B42ForagingBridge.pickupStart(self.character, self.iconID, self) or false
end
function ISForageAction:serverStop()
    if not own(self) then return fallback("serverStop", self) end
    if B42ForagingBridge then B42ForagingBridge.pickupCancel(self.character, self.iconID, self) end
end
function ISForageAction:start()
    if not own(self) then return fallback("start", self) end
    if not isClient() and not isServer() then self.b42Started = B42ForagingBridge.pickupStart(self.character, self.iconID, self)
    else self.b42Started = true end
    fallback("start", self)
    if not self.b42Started then self:forceStop() end
end
function ISForageAction:stop()
    if own(self) and not isClient() and B42ForagingBridge then B42ForagingBridge.pickupCancel(self.character, self.iconID, self) end
    return fallback("stop", self)
end
function ISForageAction:complete()
    if not own(self) then return fallback("complete", self) end
    if isClient() then return true end
    return B42ForagingBridge and B42ForagingBridge.pickupComplete(self.character, self.iconID, self, self.targetContainer) or false
end
function ISForageAction:perform()
    if not own(self) then return fallback("perform", self) end
    -- The shared delta removes the icon. Never call Vanilla actionComplete/takeItem for this ID.
    ISBaseTimedAction.perform(self)
end

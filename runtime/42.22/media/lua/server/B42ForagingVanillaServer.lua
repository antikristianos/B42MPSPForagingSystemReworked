if not isServer() then return end
require "Foraging/forageServer"

-- Affinity scans and their native personal pools remain intact. Only ordinary pool output is replaced.
local originalGenerate = forageServer.generatePool
function forageServer.generatePool(...)
    local generated = originalGenerate(...)
    local special = {}
    for id, record in pairs(generated or {}) do if record.isBonusIcon or record.catName == "Tracks" then special[id]=record end end
    return special
end

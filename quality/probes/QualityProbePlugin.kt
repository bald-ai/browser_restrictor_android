@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)
package friction.quality.compiler

import java.io.File
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.compiler.plugin.*
import org.jetbrains.kotlin.config.*
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

private val manifestKey = CompilerConfigurationKey<String>("Quality probe manifest")
class ProbeOptions : CommandLineProcessor {
    override val pluginId = "friction.quality.probes"
    override val pluginOptions = listOf(CliOption("manifest", "path", "Probe manifest", true, false))
    override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) {
        require(option.optionName == "manifest")
        configuration.put(manifestKey, value)
    }
}
class ProbeRegistrar : CompilerPluginRegistrar() {
    override val supportsK2 = true
    override val pluginId = "friction.quality.probes"
    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        IrGenerationExtension.registerExtension(ProbeExtension(configuration.get(manifestKey)!!))
    }
}
private class ProbeExtension(val manifestPath: String) : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val hit = pluginContext.referenceFunctions(CallableId(FqName("friction.quality.runtime"), Name.identifier("probeHit"))).single()
        val condition = pluginContext.referenceFunctions(CallableId(FqName("friction.quality.runtime"), Name.identifier("probeCondition"))).single()
        val manifest = mutableListOf<String>()
        for (file in moduleFragment.files) {
            val path = file.fileEntry.name.replace('\\', '/')
            if (!path.contains("/src/main/java/") && !path.contains("/quality/probes/fixtures/")) continue
            file.transformChildrenVoid(object : IrElementTransformerVoid() {
                var owner: IrFunction? = null
                var ownerId: String? = null
                var branchIndex = 0
                override fun visitFunction(declaration: IrFunction): IrStatement {
                    val prior = owner; val priorId = ownerId; val priorIndex = branchIndex
                    owner = declaration
                    ownerId = "$path:${declaration.startOffset}:${declaration.endOffset}:${declaration.name}"
                    branchIndex = 0
                    val body = declaration.body
                    if (body == null || declaration.startOffset < 0) {
                        owner = null; ownerId = null
                        declaration.transformChildrenVoid(this)
                    } else {
                        val builder = DeclarationIrBuilder(pluginContext, declaration.symbol, body.startOffset, body.endOffset)
                        declaration.transformChildrenVoid(this)
                        val id = ownerId!! + ":entry"
                        val probe = builder.irCall(hit).apply { arguments[0] = builder.irString(id) }
                        when (body) {
                            is IrBlockBody -> body.statements.add(0, probe)
                            is IrExpressionBody -> body.expression = builder.irBlock(resultType = body.expression.type) {
                                +probe
                                +body.expression
                            }
                            else -> error("Unsupported body ${body.javaClass}")
                        }
                        manifest += listOf(id, path, declaration.name.asString(), declaration.startOffset,
                            declaration.endOffset, body.startOffset, body.endOffset, declaration.origin, "entry").joinToString("\t")
                    }
                    owner = prior; ownerId = priorId; branchIndex = priorIndex
                    return declaration
                }
                override fun visitBranch(branch: IrBranch): IrBranch {
                    branch.transformChildrenVoid(this)
                    val fn = owner ?: return branch
                    val expression = branch.condition
                    // Else arms are unconditional; recording both values would invent an impossible edge.
                    if (branch is IrElseBranch || expression is IrConst) return branch
                    branch.condition = recordCondition(fn, expression)
                    return branch
                }
                override fun visitLoop(loop: IrLoop): IrExpression {
                    loop.transformChildrenVoid(this)
                    val fn = owner ?: return loop
                    // A literal loop condition has no alternative runtime outcome.
                    if (loop.condition !is IrConst) loop.condition = recordCondition(fn, loop.condition)
                    return loop
                }
                private fun recordCondition(fn: IrFunction, expression: IrExpression): IrExpression {
                    val id = ownerId!! + ":condition:" + branchIndex++
                    val builder = DeclarationIrBuilder(pluginContext, fn.symbol, expression.startOffset, expression.endOffset)
                    val call = builder.irCall(condition).apply {
                        arguments[0] = builder.irString(id); arguments[1] = expression
                    }
                    manifest += listOf(id, path, fn.name.asString(), fn.startOffset, fn.endOffset,
                        expression.startOffset, expression.endOffset, fn.origin, "condition").joinToString("\t")
                    return call
                }
            })
        }
        File(manifestPath).apply { parentFile.mkdirs(); writeText(manifest.joinToString("\n", postfix = "\n")) }
    }
}
